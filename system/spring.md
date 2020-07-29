# Spring #

## 微服务

### 什么是微服务

微服务是现代软件的一种方法论。通过让应用程序代码变成一个个小的可管理的相互独立的单元。

- 一张图了解微服务

![Microservices diagram](images\diagram-microservices-88e01c7d34c688cb49556435c130d352.svg)

### 微服务的利弊

#### 优势

这种相对较小的微型化的架构使得软件具有以下优势：维护性高（升级）、可拓展性（水平拓展）、可用性。

#### 劣势

搭建成本较高、底层所依赖的资源和企业成本也相对较高

#### 扬长弊端

适合大型商业逻辑比较复杂的业务场景。



## Spring Cloud



### 架构

![Spring Cloud diagram](images\cloud-diagram-1a4cad7294b4452864b5ff57175dd983.svg)

### 服务发现



在云中应用程序不知道彼此之间的位置，这个时候需要一个服务注册中心。比如 [Netflix Eureka](https://github.com/Netflix/eureka), [HashiCorp Consul](https://www.consul.io/)。当然其他的注册中心比如zookeeper也支持。



### 网关



在云架构中，包含一个API网关是十分重要的事情。好处有：

- 加密与认证：统一加密和认证（单点登录）

- 路由消息、隐藏服务
- 负载均衡
- 跨域问题
- 解耦：实现客户端与微服务的解耦，由网关做统一映射

#### Spring Cloud Gateway

##### 工作原理

![Spring Cloud Gateway Diagram](images\spring_cloud_gateway_diagram.png)

##### 同类产品对比

###### soul

https://dromara.org/website/zh-cn/docs/soul/soul.html



#### 参考

常见网关对比：

https://blog.csdn.net/qq_29281307/article/details/90235261