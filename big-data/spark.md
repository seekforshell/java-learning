# Spark

## 编译

### 编译发布包

-P表示是否将相关依赖加载到jars中

```
./dev/make-distribution.sh --name starry --pip --r --tgz -P sparkr -P hive -P hive-thriftserver -P mesos -P yarn -P kubernetes -P hadoop-provided
```



### 安装R语言包

https://cloud.r-project.org/

```
R_HOME=/Library/Frameworks/R.framework/Resources
```

打包命令



## Spark3 安装问题

### 坏的替换问题

```
[2021-04-26 20:42:27.949]Container exited with a non-zero exit code 1. Error file: prelaunch.err.
Last 4096 bytes of prelaunch.err :
r/hdp/current/hadoop-yarn-client/*:/usr/hdp/current/hadoop-yarn-client/lib/*:$PWD/mr-framework/hadoop/share/hadoop/mapreduce/*:$PWD/mr-framework/hadoop/share/hadoop/mapreduce/lib/*:$PWD/mr-framework/hadoop/share/hadoop/common/*:$PWD/mr-framework/hadoop/share/hadoop/common/lib/*:$PWD/mr-framework/hadoop/share/hadoop/yarn/*:$PWD/mr-framework/hadoop/share/hadoop/yarn/lib/*:$PWD/mr-framework/hadoop/share/hadoop/hdfs/*:$PWD/mr-framework/hadoop/share/hadoop/hdfs/lib/*:$PWD/mr-framework/hadoop/share/hadoop/tools/lib/*:/usr/hdp/${hdp.version}/hadoop/lib/hadoop-lzo-0.6.0.${hdp.version}.jar:/etc/hadoop/conf/secure:/usr/hdp/3.1.0.0-78/hadoop/conf:/usr/hdp/3.1.0.0-78/hadoop/lib/*:
......
1.7.10.jar:/usr/hdp/3.1.0.0-78/tez/lib/tez.tar.gz:$PWD/__spark_conf__/__hadoop_conf__: 坏的替换
```

- 解决方案

mapred-site.xml添加键值对

```

  <property>
    <name>hdp.version</name>
    <value>3.1.0.0-78</value>
    <description>
     used for mapreduce job 
    </description>
    <on-ambari-upgrade add="false"/>
  </property>
```

