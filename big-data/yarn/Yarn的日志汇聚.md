## 日志汇聚配置



| 常用配置                                       | 作用                         | 说明 |
| ---------------------------------------------- | ---------------------------- | ---- |
| yarn.log-aggregation-enable                    | 日志聚合功能是否开启         |      |
| yarn.log-aggregation.retain-seconds            | 日志聚合保留时间             |      |
| yarn.nodemanager.log-aggregation.debug-enabled | nodemanager日志聚合debug信息 |      |



## 日志汇聚服务实现类

日志汇聚的主要实现逻辑在LogAggregationService类中。

**初始化：**

ContainerManagerImpl.java 进行初始化的时候会创建logHandler这个时候会根据日志汇聚使能开关来决定生成loghandler的方式。

```java
protected LogHandler createLogHandler(Configuration conf, Context context,
    DeletionService deletionService) {
  // 使能日志汇聚则生成日志汇聚处理类
  if (conf.getBoolean(YarnConfiguration.LOG_AGGREGATION_ENABLED,
      YarnConfiguration.DEFAULT_LOG_AGGREGATION_ENABLED)) {
    return new LogAggregationService(this.dispatcher, context,
        deletionService, dirsHandler);
  } else {
    return new NonAggregatingLogHandler(this.dispatcher, deletionService,
                                        dirsHandler,
                                        context.getNMStateStore());
  }
}
```

**主要成员变量：**

dispather可以接受事件的注册并在事件下发时根据注册事件类型做出相应回调。比如：GenericEventHandler

dispatcher是在ContainerManagerImpl.java初始化的时候生成的。

```java
public LogAggregationService(Dispatcher dispatcher, Context context,
    DeletionService deletionService, LocalDirsHandlerService dirsHandler) {
  super(LogAggregationService.class.getName());
  this.dispatcher = dispatcher;
  this.context = context;
  this.deletionService = deletionService;
  this.dirsHandler = dirsHandler;
  this.appLogAggregators =
      new ConcurrentHashMap<ApplicationId, AppLogAggregator>();
  this.invalidTokenApps = ConcurrentHashMap.newKeySet();
}
```

日志汇聚服务的生命周期主要体现在以下几个事件中

| 事件                 | 主要动作                                                     |      |
| -------------------- | ------------------------------------------------------------ | ---- |
| APPLICATION_STARTED  | 初始化日志聚合功能，并启动后台聚合线程AppLogAggregator，后台检测等待聚合事件到来；并负责向rm发送日志聚合报告 |      |
| CONTAINER_FINISHED   | 通知聚合线程开始进行对此容器日志聚合                         |      |
| APPLICATION_FINISHED | 结束日志聚合，设置全局变量通知聚合线程销毁资源               |      |
| LOG_AGG_TOKEN_UPDATE |                                                              |      |

主要代码逻辑，感兴趣可以跟着往下走看下

```java
  public void handle(LogHandlerEvent event) {
    switch (event.getType()) {
      case APPLICATION_STARTED:
        LogHandlerAppStartedEvent appStartEvent =
            (LogHandlerAppStartedEvent) event;
        initApp(appStartEvent.getApplicationId(), appStartEvent.getUser(),
            appStartEvent.getCredentials(),
            appStartEvent.getApplicationAcls(),
            appStartEvent.getLogAggregationContext(),
            appStartEvent.getRecoveredAppLogInitedTime());
        break;
      case CONTAINER_FINISHED:
        LogHandlerContainerFinishedEvent containerFinishEvent =
            (LogHandlerContainerFinishedEvent) event;
        stopContainer(containerFinishEvent.getContainerId(),
            containerFinishEvent.getContainerType(),
            containerFinishEvent.getExitCode());
        break;
      case APPLICATION_FINISHED:
        LogHandlerAppFinishedEvent appFinishedEvent =
            (LogHandlerAppFinishedEvent) event;
        stopApp(appFinishedEvent.getApplicationId());
        break;
      case LOG_AGG_TOKEN_UPDATE:
        checkAndEnableAppAggregators();
        break;
      default:
        ; // Ignore
    }

  }
```

那么汇聚服务上传了哪些日志呢？

NodeManager本地日志目录配置，汇聚服务会将此目录下的日志上传。

```java
# 
public static final String NM_PREFIX = "yarn.nodemanager.";
public static final String NM_LOG_DIRS = NM_PREFIX + "log-dirs";
```

比如nodemanager本地日志，容器里的日志会存储在此并在程序结束时汇聚到hdfs上

```
# ll /hadoop/yarn/log/application_1626170049041_0016/container_e54_1626170049041_0016_02_000001/
总用量 4
-rw-r--r-- 1 hdfs hadoop 1750 7月  14 15:02 container-localizer-syslog

```

