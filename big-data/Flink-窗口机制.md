## 概述

首先这里我们讲下流计算中的窗口概念，窗口是了处理一段时间内的流数据提出来的一个概念；窗口有生命周期，当然也有全局窗口（例外，长生不老的那种 --! 。在流数据中开了个窗口你可以理解为在河流分支上开了个水池，就像一个缓存池一样，满了就释放掉；那么窗口可以用来做什么呢？窗口可以做聚合、join、统计等功能。

## stream join使用示例

这里我们采用flink中的event time属性来定义数据的时间。

那么做流join其实需要六步操作接即可，即step-1~step-6。接下来

```java
			DataStream<RowData> resOfSink = rankDs
					.assignTimestampsAndWatermarks(...)  // step-1
					.join(goodsDs)                       // step-2
					.where((KeySelector<OrderRankEntry, String>) OrderRankEntry::getGoodsId)  // step-3
					.equalTo((KeySelector<GoodsDetail, String>) GoodsDetail::getGoodsId) // step-4
					.window(TumblingEventTimeWindows.of(Time.seconds(10)))   // step-5
					.apply(                              // step-6
							(rankEntry, second) -> {
								rankEntry.setGoodName(second.getGoodsName());
							return rankEntry;
					}).map(e -> {                       // step-7
						String rankId = e.getRankId();
						return GenericRowData.of(StringData.fromString(rankId),
								StringData.fromString(e.getGoodsId()),
								StringData.fromString(e.getGoodName()),
								TimestampData.fromEpochMillis(e.getRankTime().getTime()));
					});
```

### step-1 设置watermark和定义数据时间属性

- WHAT

assignTimestampsAndWatermarks的入参类型是水印策略的接口，这里有两个功能一个是生成watermark（水位或者理解为水位线）；一个是抽取数据的时间戳，也就是说对与每一条流数据你可以自定义如何获取数据的时间属性，比如是从数据本身携带的时间属性还是系统处理数据的时间等。

- WHY

那么定义这些有啥用呢？因为网络延时等客观原因数据可能会有迟到或者乱序，水位线的作用就在于可以定义一个数据延迟的最大时间，比如一个窗口它的生命周期为12:00-12:05分，那么我定义一个最大延迟时间为10s，那么12:05:10内的数据是不算迟到的，这个逻辑会在窗口算子中进行处理。

```java
public interface WatermarkStrategy<T> extends
      TimestampAssignerSupplier<T>, WatermarkGeneratorSupplier<T> {
  ...
}
```

- HOW

下面就结合一个具体实例来说下水位线是如何实现的，这里说明下datastream添加的各种算子会在execute的时候通过StreamGraphGenerator#generate转变为streamgraph。其中WatermarkGenerator的实现也会被封装为TimestampsAndWatermarksOperator算子。最终用户自定义的水位线策略会在该算子中执行。

水位线算子在处理流数据时会获取数据的时间属性，然后调用onEvent方法，

比如有界乱序水位线策略记录目前为止最大的时间戳值。那么什么时候会触发水位呢？那么这里会涉及到另外一个方法onProcessingTime，这个方法是**系统处理事件服务**的回调，此处用来向下游节点发送watermark。

```java
// TimestampsAndWatermarksOperator.java
public void processElement(final StreamRecord<T> element) throws Exception {
    final T event = element.getValue();
    final long previousTimestamp =
            element.hasTimestamp() ? element.getTimestamp() : Long.MIN_VALUE;
  	// 调用定义的时间戳抽取函数	
    final long newTimestamp = timestampAssigner.extractTimestamp(event, previousTimestamp);
    element.setTimestamp(newTimestamp);
    output.collect(element);
  	// 调用onEvent方法。
    watermarkGenerator.onEvent(event, newTimestamp, wmOutput);
}

public void onProcessingTime(long timestamp) throws Exception {
 	// 调用watermark策略输出水位，就像是泄洪信号一样，会发给给下游算子的窗口算子
  watermarkGenerator.onPeriodicEmit(wmOutput);

  final long now = getProcessingTimeService().getCurrentProcessingTime();
  // 根据系统时间注册当前时间后的一段时间-watermark-internal (间隔时间可以通过flink参数设置)
  getProcessingTimeService().registerTimer(now + watermarkInterval, this);
}
```

```java
public class BoundedOutOfOrdernessWatermarks<T> implements WatermarkGenerator<T> {

    /** The maximum timestamp encountered so far. */
    private long maxTimestamp;

    /** The maximum out-of-orderness that this watermark generator assumes. */
    private final long outOfOrdernessMillis;

    /**
     * Creates a new watermark generator with the given out-of-orderness bound.
     *
     * @param maxOutOfOrderness The bound for the out-of-orderness of the event timestamps.
     */
    public BoundedOutOfOrdernessWatermarks(Duration maxOutOfOrderness) {
        checkNotNull(maxOutOfOrderness, "maxOutOfOrderness");
        checkArgument(!maxOutOfOrderness.isNegative(), "maxOutOfOrderness cannot be negative");

        this.outOfOrdernessMillis = maxOutOfOrderness.toMillis();

        // start so that our lowest watermark would be Long.MIN_VALUE.
        this.maxTimestamp = Long.MIN_VALUE + outOfOrdernessMillis + 1;
    }

    // ------------------------------------------------------------------------

    @Override
    public void onEvent(T event, long eventTimestamp, WatermarkOutput output) {
        maxTimestamp = Math.max(maxTimestamp, eventTimestamp);
    }

    @Override
    public void onPeriodicEmit(WatermarkOutput output) {
        output.emitWatermark(new Watermark(maxTimestamp - outOfOrdernessMillis - 1));
    }
}
```

### step2~5 join/where/equal/window/apply 设置要Join的流

这几个方法放到一起来说，是因为这几个方法是实现流join的必要步骤。

join决定跟那个流做笛卡尔积，where表示左流的过滤条件，equal表示右表，window表示两个流共用的窗口，因为流计算是

无法直接做笛卡尔积的所以它需要有个窗口来让两个流共同进入到这个窗口才能进行join操作。apply就是告诉flink如何完成这个操作。

这里重点讲下window和apply是如何实现流join的。

指定窗口需要实现WindowAssigner接口，包括定义开窗、窗口触发器等。窗口策略的执行跟watermark一样也有相应的算子即WindowOperator。因为每一个算子都会有序处理流数据，所以同样可以通过processElement方法来了解他们的核心逻辑。

- 窗口-开窗

    这里以滚动窗口处理事件事件为例，简单说下如何决定窗口的生命周期。比如12:00:11的数据，10s的窗口。那么其窗口起始为：

    12:00:10 - 12:00:20

```java
	public Collection<TimeWindow> assignWindows(Object element, long timestamp, WindowAssignerContext context) {
		if (timestamp > Long.MIN_VALUE) {
      // 窗口偏移
			if (staggerOffset == null) {
				staggerOffset = windowStagger.getStaggerOffset(context.getCurrentProcessingTime(), size);
			}
			// Long.MIN_VALUE is currently assigned when no timestamp is present
			long start = TimeWindow.getWindowStartWithOffset(timestamp, (globalOffset + staggerOffset) % size, size);
			return Collections.singletonList(new TimeWindow(start, start + size));
		} else {
			throw new RuntimeException("Record has Long.MIN_VALUE timestamp (= no timestamp marker). " +
					"Is the time characteristic set to 'ProcessingTime', or did you forget to call " +
					"'DataStream.assignTimestampsAndWatermarks(...)'?");
		}
	}
```

- 窗口-处理流数据

    a.流数据进来后开窗

    b.窗口遍历

    c.判断窗口是否过期

    d.是否触发窗口。如果是进行步骤e

    这里以

    e.注册窗口清理回调，保证时间达到窗口结束时间时发送数据到下游

    这里有个清理时间的概念具体见下，根据不同时间来定义

    ```java
    private long cleanupTime(W window) {
       if (windowAssigner.isEventTime()) {
          long cleanupTime = window.maxTimestamp() + allowedLateness;
          return cleanupTime >= window.maxTimestamp() ? cleanupTime : Long.MAX_VALUE;
       } else {
          return window.maxTimestamp();
       }
    }
    ```

    窗口处理数据核心代码

    ```java
    // 	WindowOperator
    public void processElement(StreamRecord<IN> element) throws Exception {
      // a.流数据进来后开窗
    	final Collection<W> elementWindows = windowAssigner.assignWindows(
    			element.getValue(), element.getTimestamp(), windowAssignerContext);
    	//if element is handled by none of assigned elementWindows
    	boolean isSkippedElement = true;
    
    	final K key = this.<K>getKeyedStateBackend().getCurrentKey();
    	// 合并窗口逻辑。这里我们简化看else分支的代码也可以了解窗口的逻辑
    	if (windowAssigner instanceof MergingWindowAssigner) {
    		...
    	} else {
      // b.遍历窗口
    		for (W window: elementWindows) {
    
    			// c.判断窗口过期。
    			if (isWindowLate(window)) {
    				continue;
    			}
    			isSkippedElement = false;
    
    			windowState.setCurrentNamespace(window);
    			windowState.add(element.getValue());
    
    			triggerContext.key = key;
    			triggerContext.window = window;
    
        // d.是否触发窗口 
    			TriggerResult triggerResult = triggerContext.onElement(element);
    
    			if (triggerResult.isFire()) {
    				ACC contents = windowState.get();
    				if (contents == null) {
    					continue;
    				}
          // 发送数据到下游
    				emitWindowContents(window, contents);
    			}
    
    			if (triggerResult.isPurge()) {
    				windowState.clear();
    			}
        // e.注册窗口清理回调，保证时间达到窗口结束时间时发送数据到下游
    			registerCleanupTimer(window);
    		}
    	}
    
    	// side output input event if
    	// element not handled by any window
    	// late arriving tag has been set
    	// windowAssigner is event time and current timestamp + allowed lateness no less than element timestamp
    	if (isSkippedElement && isElementLate(element)) {
    		if (lateDataOutputTag != null){
    			sideOutput(element);
    		} else {
    			this.numLateRecordsDropped.inc();
    		}
    	}
    }
    ```

- apply

    apply函数，是流join的核心逻辑。

    此步骤其实是窗口真正赋值的地方，Input1和input2合并成一个流之后然后设置了窗口、触发器等设置。也就是从这里可以看出其实两个流用的同一个窗口。

    ```java
    public <T> DataStream<T> apply(JoinFunction<T1, T2, T> function, TypeInformation<T> resultType) {
      //clean the closure
      function = input1.getExecutionEnvironment().clean(function);
    
      coGroupedWindowedStream = input1.coGroup(input2)
        .where(keySelector1)
        .equalTo(keySelector2)
        .window(windowAssigner)
        .trigger(trigger)
        .evictor(evictor)
        .allowedLateness(allowedLateness);
    
      return coGroupedWindowedStream
        .apply(new JoinCoGroupFunction<>(function), resultType);
    }
    ```

    从apply方法继续往下跟踪，可以看到如下代码，此处的目的在于将input1流和input2流做了个个map转换，将数据类型分别

    转化内Input1Tagger和Input2Tagger，此步骤的目的是方便后面进行实际join时判断数据是左流还是右流。

    ```java
    public <T> DataStream<T> apply(CoGroupFunction<T1, T2, T> function, TypeInformation<T> resultType) {
    			//clean the closure
    			function = input1.getExecutionEnvironment().clean(function);
    
    			UnionTypeInfo<T1, T2> unionType = new UnionTypeInfo<>(input1.getType(), input2.getType());
    			UnionKeySelector<T1, T2, KEY> unionKeySelector = new UnionKeySelector<>(keySelector1, keySelector2);
    
    			DataStream<TaggedUnion<T1, T2>> taggedInput1 = input1
    					.map(new Input1Tagger<T1, T2>())
    					.setParallelism(input1.getParallelism())
    					.returns(unionType);
    			DataStream<TaggedUnion<T1, T2>> taggedInput2 = input2
    					.map(new Input2Tagger<T1, T2>())
    					.setParallelism(input2.getParallelism())
    					.returns(unionType);
    
    			DataStream<TaggedUnion<T1, T2>> unionStream = taggedInput1.union(taggedInput2);
    
    			// we explicitly create the keyed stream to manually pass the key type information in
    			windowedStream =
    					new KeyedStream<TaggedUnion<T1, T2>, KEY>(unionStream, unionKeySelector, keyType)
    					.window(windowAssigner);
    
    			if (trigger != null) {
    				windowedStream.trigger(trigger);
    			}
    			if (evictor != null) {
    				windowedStream.evictor(evictor);
    			}
    			if (allowedLateness != null) {
    				windowedStream.allowedLateness(allowedLateness);
    			}
    
    			return windowedStream.apply(new CoGroupWindowFunction<T1, T2, T, KEY, W>(function), resultType);
    }
    ```

    再继续往下跟踪，发现function封装在了CoGroupWindowFunction中。

    这里就是遍历所有的左流和右流的所有数据然后执行coGroup进行实际的join，自此流的join逻辑基本分析完。

    ```java
    public void apply(KEY key,
          W window,
          Iterable<TaggedUnion<T1, T2>> values,
          Collector<T> out) throws Exception {
    
       List<T1> oneValues = new ArrayList<>();
       List<T2> twoValues = new ArrayList<>();
    
       for (TaggedUnion<T1, T2> val: values) {
          if (val.isOne()) {
             oneValues.add(val.getOne());
          } else {
             twoValues.add(val.getTwo());
          }
       }
       wrappedFunction.coGroup(oneValues, twoValues, out);
    }
    ```