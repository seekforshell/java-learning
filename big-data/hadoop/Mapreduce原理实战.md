# Mapreduce原理实战





### split算法



```
  public static <T extends InputSplit> void createSplitFiles(Path jobSubmitDir, 
      Configuration conf, FileSystem fs, T[] splits) 
  throws IOException, InterruptedException {
    FSDataOutputStream out = createFile(fs, 
        JobSubmissionFiles.getJobSplitFile(jobSubmitDir), conf);
    SplitMetaInfo[] info = writeNewSplits(conf, splits, out);
    out.close();
    writeJobSplitMetaInfo(fs,JobSubmissionFiles.getJobSplitMetaFile(jobSubmitDir), 
        new FsPermission(JobSubmissionFiles.JOB_FILE_PERMISSION), splitVersion,
        info);
  }
```



查看staging位置：

```
yarn.app.mapreduce.am.staging-dir
```

```
[root@emr112 ~]# hdfs dfs -ls /user/hdfs/.staging/job_1626246900037_0005
Found 4 items
-rw-r--r--  10 hdfs hdfs     183803 2021-07-14 15:27 /user/hdfs/.staging/job_1626246900037_0005/job.jar
-rw-r--r--  10 hdfs hdfs        691 2021-07-14 15:27 /user/hdfs/.staging/job_1626246900037_0005/job.split
-rw-r--r--   1 hdfs hdfs         43 2021-07-14 15:27 /user/hdfs/.staging/job_1626246900037_0005/job.splitmetainfo
-rw-r--r--   1 hdfs hdfs     212010 2021-07-14 15:27 /user/hdfs/.staging/job_1626246900037_0005/job.xml

```



## MR实例



```java
public void run(FsAnalyserParams request) throws Exception {
   // new mapper api
   Job job = Job.getInstance(getConf(), "FsAnalyserMr");

   job.setJarByClass(FsAnalyserMr.class);

   job.setInputFormatClass(TextInputFormat.class);
   job.setOutputFormatClass(TextOutputFormat.class);
   job.setMapperClass(FsMapper.class);
   job.setReducerClass(FsReducer.class);
   // set map output key & value type
   job.setMapOutputKeyClass(Text.class);
   job.setMapOutputValueClass(Text.class);
   job.addFileToClassPath(new Path(request.getAppClassPath()));
   job.setJar("core-1.0-SNAPSHOT.jar");

   // 设置过滤策略
   ObjectMapper mapper = new ObjectMapper();
   job.getConfiguration().set(FsAnalysePolicy.POLICY_KEY, mapper.writeValueAsString(request.getFilterPolicy()));
   FileInputFormat.setInputPaths(job, request.getFsImagePath());
   FileOutputFormat.setOutputPath(job, new Path(request.getOuputPath()));

   try {
      job.waitForCompletion(true);
   } catch (Exception e){
      logger.error("run job error", e);
      throw e;
   }
}
```

Mapper:

```java
public class FsMapper extends Mapper<LongWritable, Text, Text, Text> {
	private static Logger logger = LoggerFactory.getLogger(FsMapper.class);

	public boolean isDir(Map<String, String> entryMap) {
		return entryMap.get("Permission").startsWith("d");
	}

	public void map(LongWritable lineNum, Text row,
			Context context) throws IOException, InterruptedException {

		ObjectMapper policyMapper = new ObjectMapper();
		FsAnalysePolicy mapPolicy = policyMapper.readValue(context.getConfiguration().get("rowJson.mapPolicy"), FsAnalysePolicy.class);

		List<String> pathList = new ArrayList<>();
		Optional.of(mapPolicy.getFsPathList()).ifPresent(p -> Collections.addAll(pathList, p.split(",")));
		// skip head line in oiv
		if (row.toString().contains("NSQUOTA")) {
			return;
		}

		String[] fieldVal = row.toString().split(",");
		Map<String, String> entryMap = new LinkedHashMap<>();
		int i = 0;
		for (HashMap.Entry<String, Integer> e : FsAnalyserMr.metaInfo.entrySet()) {
			entryMap.put(e.getKey(), fieldVal[i++]);
		}

		if (isDir(entryMap)) {
			return;
		}

		// path filter
		File f = new File(entryMap.get("Path").trim());
		if (!pathList.isEmpty()) {
			for (String p : pathList) {
				if (!f.getAbsolutePath().startsWith(p)) {
					logger.info("absolute path:{}", f.getAbsolutePath());
					return;
				}
			}
		}

		// small file filter
		AnalysePolicyBody analysePolicy = mapPolicy.getPolicyBody();
		if (SMALL_FILE.typeName().equals(analysePolicy.getPolicyType())) {
			if (!SmallFilePolicy.belongTo(analysePolicy.getPolicyParam(), Long.valueOf(entryMap.get("FileSize")))) {
				return;
			}
		}

		context.write(new Text(f.getParent()), row);
		logger.info("map info: lineNum:{}, row:{}", f.getParent(), row);
	}
}
```

Reduce:

```java
public class FsReducer extends Reducer<Text, Text, Text, Text> {
	private static Logger logger = LoggerFactory.getLogger(FsReducer.class);
	@Override
	protected void reduce(Text key, Iterable<Text> values, Context context) throws IOException, InterruptedException {
		ObjectMapper policyMapper = new ObjectMapper();
		FsAnalysePolicy policy = policyMapper.readValue(context.getConfiguration().get("mapper.policy"), FsAnalysePolicy.class);

		if (SMALL_FILE.typeName().equals(policy.getPolicyBody().getPolicyType())) {
			if (!StringUtil.isEmpty(policy.getFsPathList())) {
				List<FileMetaInfo> metaInfoList = new ArrayList<>();
				values.forEach(e -> {
					try {
						Optional.ofNullable(FileMetaInfoTool.newEntry(e.toString())).ifPresent(metaInfoList::add);
					} catch (NoSuchFieldException | IllegalAccessException | ParseException ex) {
						logger.error("new file metainfo error", ex);
					}
				});

				metaInfoList.sort((o1, o2) -> {
					if (o1.getFileSize() > o2.getFileSize()) {
						return 1;
					} else {
						return 0;
					}
				});

				AnalyseMiddleResult middleResult = new AnalyseMiddleResult();
				middleResult.setPath(key.toString());
				middleResult.setChildNameList(metaInfoList.stream().map(FileMetaInfo::getPath).collect(Collectors.toList()));
				ObjectMapper mapper = new ObjectMapper();
				context.write(key, new Text(mapper.writeValueAsString(middleResult)));
			} else {
				AtomicLong sum = new AtomicLong(0);
				values.forEach(e -> sum.incrementAndGet());
				logger.info("### key:{}, value:{}", key, sum.get());
				context.write(key, new Text(String.valueOf(sum.get())));
			}
		}
	}
}
```

