## 算子

org.apache.flink.streaming.runtime.operators.windowing.WindowOperator

以WindowedStream的reduce方法为例：

```java
public <R> SingleOutputStreamOperator<R> reduce(ReduceFunction<T> reduceFunction, ProcessWindowFunction<T, R, K, W> function, TypeInformation<R> resultType) {
		if (reduceFunction instanceof RichFunction) {
			throw new UnsupportedOperationException("ReduceFunction of apply can not be a RichFunction.");
		}
		//clean the closures
		function = input.getExecutionEnvironment().clean(function);
		reduceFunction = input.getExecutionEnvironment().clean(reduceFunction);

		final String opName = generateOperatorName(windowAssigner, trigger, evictor, reduceFunction, function);
		KeySelector<T, K> keySel = input.getKeySelector();

		OneInputStreamOperator<T, R> operator;
		// 如果有指定驱逐策略走此
		if (evictor != null) {
			@SuppressWarnings({"unchecked", "rawtypes"})
			TypeSerializer<StreamRecord<T>> streamRecordSerializer =
					(TypeSerializer<StreamRecord<T>>) new StreamElementSerializer(input.getType().createSerializer(getExecutionEnvironment().getConfig()));

			ListStateDescriptor<StreamRecord<T>> stateDesc =
					new ListStateDescriptor<>("window-contents", streamRecordSerializer);

			operator =
					new EvictingWindowOperator<>(windowAssigner,
							windowAssigner.getWindowSerializer(getExecutionEnvironment().getConfig()),
							keySel,
							input.getKeyType().createSerializer(getExecutionEnvironment().getConfig()),
							stateDesc,
							new InternalIterableProcessWindowFunction<>(new ReduceApplyProcessWindowFunction<>(reduceFunction, function)),
							trigger,
							evictor,
							allowedLateness,
							lateDataOutputTag);

		} else {
      // 生成state描述符
			ReducingStateDescriptor<T> stateDesc = new ReducingStateDescriptor<>("window-contents",
					reduceFunction,
					input.getType().createSerializer(getExecutionEnvironment().getConfig()));
			// 生成窗口算子，包括windows assigner, processwindow, trigger等
			operator =
					new WindowOperator<>(windowAssigner,
							windowAssigner.getWindowSerializer(getExecutionEnvironment().getConfig()),
							keySel,
							input.getKeyType().createSerializer(getExecutionEnvironment().getConfig()),
							stateDesc,
							new InternalSingleValueProcessWindowFunction<>(function),
							trigger,
							allowedLateness,
							lateDataOutputTag);
		}

		return input.transform(opName, resultType, operator);
	}
```



当有消息或者数据元素流入时会调用到WindowOperator的processElement接口



```java
public void processElement(StreamRecord<IN> element) throws Exception {
   // 获取元素的窗口集合：可能是滑动、滚动等
   final Collection<W> elementWindows = windowAssigner.assignWindows(
      element.getValue(), element.getTimestamp(), windowAssignerContext);

   //if element is handled by none of assigned elementWindows
   boolean isSkippedElement = true;

   final K key = this.<K>getKeyedStateBackend().getCurrentKey();

   if (windowAssigner instanceof MergingWindowAssigner) {
      MergingWindowSet<W> mergingWindows = getMergingWindowSet();

      for (W window: elementWindows) {

         // adding the new window might result in a merge, in that case the actualWindow
         // is the merged window and we work with that. If we don't merge then
         // actualWindow == window
         W actualWindow = mergingWindows.addWindow(window, new MergingWindowSet.MergeFunction<W>() {
            @Override
            public void merge(W mergeResult,
                  Collection<W> mergedWindows, W stateWindowResult,
                  Collection<W> mergedStateWindows) throws Exception {

               if ((windowAssigner.isEventTime() && mergeResult.maxTimestamp() + allowedLateness <= internalTimerService.currentWatermark())) {
                  throw new UnsupportedOperationException("The end timestamp of an " +
                        "event-time window cannot become earlier than the current watermark " +
                        "by merging. Current watermark: " + internalTimerService.currentWatermark() +
                        " window: " + mergeResult);
               } else if (!windowAssigner.isEventTime()) {
                  long currentProcessingTime = internalTimerService.currentProcessingTime();
                  if (mergeResult.maxTimestamp() <= currentProcessingTime) {
                     throw new UnsupportedOperationException("The end timestamp of a " +
                        "processing-time window cannot become earlier than the current processing time " +
                        "by merging. Current processing time: " + currentProcessingTime +
                        " window: " + mergeResult);
                  }
               }

               triggerContext.key = key;
               triggerContext.window = mergeResult;

               triggerContext.onMerge(mergedWindows);

               for (W m: mergedWindows) {
                  triggerContext.window = m;
                  triggerContext.clear();
                  deleteCleanupTimer(m);
               }

               // merge the merged state windows into the newly resulting state window
               windowMergingState.mergeNamespaces(stateWindowResult, mergedStateWindows);
            }
         });

         // drop if the window is already late
         if (isWindowLate(actualWindow)) {
            mergingWindows.retireWindow(actualWindow);
            continue;
         }
         isSkippedElement = false;

         W stateWindow = mergingWindows.getStateWindow(actualWindow);
         if (stateWindow == null) {
            throw new IllegalStateException("Window " + window + " is not in in-flight window set.");
         }

         windowState.setCurrentNamespace(stateWindow);
         windowState.add(element.getValue());

         triggerContext.key = key;
         triggerContext.window = actualWindow;

         TriggerResult triggerResult = triggerContext.onElement(element);

         if (triggerResult.isFire()) {
            ACC contents = windowState.get();
            if (contents == null) {
               continue;
            }
            emitWindowContents(actualWindow, contents);
         }

         if (triggerResult.isPurge()) {
            windowState.clear();
         }
         registerCleanupTimer(actualWindow);
      }

      // need to make sure to update the merging state in state
      mergingWindows.persist();
   } else {
     	// 遍历窗口
      for (W window: elementWindows) {

         // drop if the window is already late
         if (isWindowLate(window)) {
            continue;
         }
         isSkippedElement = false;

         windowState.setCurrentNamespace(window);
         windowState.add(element.getValue());

         triggerContext.key = key;
         triggerContext.window = window;
				 // 以EvenetTimeTrigger为例，当元素的值大于窗口end时触发fire操作
         TriggerResult triggerResult = triggerContext.onElement(element);

         if (triggerResult.isFire()) {
            ACC contents = windowState.get();
            if (contents == null) {
               continue;
            }
           	// 收集所有窗口数据到collector
            emitWindowContents(window, contents);
         }

         if (triggerResult.isPurge()) {
            windowState.clear();
         }
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

