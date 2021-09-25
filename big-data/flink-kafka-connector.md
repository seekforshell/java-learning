# 概述

剖析kafka connector源码。



# Connector

如果需要实现自定义connector的sink和source需要实现以下接口：

```
DynamicTableSourceFactory
DynamicTableSinkFactory
```



# 数据源实现



## KafkaDynamicSource





