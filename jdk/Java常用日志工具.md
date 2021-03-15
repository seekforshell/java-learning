# Java常用日志工具





| 库              | 作用                                                         | 使用                                                 |
| --------------- | ------------------------------------------------------------ | ---------------------------------------------------- |
| Commons Logging | Commons Logging是一个第三方日志库，它是由Apache创建的日志模块默认情况下，Commons Loggin自动搜索并使用Log4j（Log4j是另一个流行的日志系统），如果没有找到Log4j，再使用JDK Logging。 | Log log = LogFactory.getLog(Main.class);             |
| Log4j           |                                                              | 如果使用log4j遇到问题，可以用-Dlog4j.debug来进行调试 |
| SLF4J           | 类似于Commons Logging，也是一个日志接口，而Logback类似于Log4j，是一个日志的实现 |                                                      |
| Logback         | Logback类似于Log4j                                           |                                                      |



## log4j	



### 使用步骤

#### 1.配置log4j.properties文件

配置文件参考，放在resource目录下：

可以通过参数配置：

```properties
# root logger配置，必须配置，如果找不到自定义的Logger会使用rootLogger
log4j.rootLogger=INFO, console, logFile
log4j.additivity.org.apache=true

# 配置logger的appender
# a.文件appender
log4j.appender.logFile.Append = true
log4j.appender.logFile = org.apache.log4j.FileAppender
# jvm 参数中添加-Dlog.file
log4j.appender.logFile.File = ${log.file}
log4j.appender.logFile.layout=org.apache.log4j.PatternLayout
log4j.appender.logFile.layout.ConversionPattern=[%-5p] %d(%r) --> [%t] %l: %m %x %n

# b.Rolling file appender 配置
log4j.appender.dailyFile=org.apache.log4j.DailyRollingFileAppender
log4j.appender.dailyFile.Threshold=DEBUG
log4j.appender.dailyFile.ImmediateFlush=true
log4j.appender.dailyFile.Append=true
# jvm 参数中添加-Dlog.file
log4j.appender.dailyFile.File=${log.file}
log4j.appender.dailyFile.DatePattern='.'yyyy-MM-dd
log4j.appender.dailyFile.layout=org.apache.log4j.PatternLayout
log4j.appender.dailyFile.layout.ConversionPattern=[%-5p] %d(%r) --> [%t] %l: %m %x %n

# c.console appender配置
log4j.appender.console=org.apache.log4j.ConsoleAppender
log4j.appender.console.name = ConsoleAppender
log4j.appender.console.type = CONSOLE
log4j.appender.console.layout=org.apache.log4j.PatternLayout
log4j.appender.console.layout.ConversionPattern=[%-5p] %d(%r) --> [%t] %l: %m %x %n
# 自定义Logger配置
log4j.logger.yarn.name = yarn.demo
log4j.logger.yarn.level = INFO
log4j.logger.yarn.appenderRef.console.ref = FileAppender
log4j.logger.hadoop.name = org.apache.hadoop
log4j.logger.hadoop.level = INFO
log4j.logger.hadoop.appenderRef.console.ref = FileAppender
```



#### 2.引入依赖包

依赖包pom.xml

```xml
<!-- 日志实现 -->
<dependency>
    <groupId>org.apache.logging.log4j</groupId>
    <artifactId>log4j-core</artifactId>
    <version>${log4j.version}</version>
</dependency>
<!-- 日志框架(门面) -->
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-api</artifactId>
    <version>${slf4j.version}</version>
</dependency>
<!-- 日志桥接包   桥接包的版本须对应log4j2的版本 -->
<dependency>
    <!-- API bridge between log4j 1 and 2 -->
    <groupId>org.apache.logging.log4j</groupId>
    <artifactId>log4j-1.2-api</artifactId>
    <version>${log4j.version}</version>
</dependency>
```



#### 3.代码

```java
private static final Logger LOG = LoggerFactory.getLogger(AppMstrDemo.class);
```



