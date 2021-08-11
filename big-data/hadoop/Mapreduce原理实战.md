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



