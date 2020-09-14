Dubbo源码解析



```java
export:197, ServiceConfig (com.alibaba.dubbo.config)
export:266, ServiceBean (com.alibaba.dubbo.config.spring)
onApplicationEvent:106, ServiceBean (com.alibaba.dubbo.config.spring)
onApplicationEvent:53, ServiceBean (com.alibaba.dubbo.config.spring)
invokeListener:166, SimpleApplicationEventMulticaster (org.springframework.context.event)
multicastEvent:138, SimpleApplicationEventMulticaster (org.springframework.context.event)
publishEvent:382, AbstractApplicationContext (org.springframework.context.support)
publishEvent:336, AbstractApplicationContext (org.springframework.context.support)
finishRefresh:877, AbstractApplicationContext (org.springframework.context.support)
refresh:544, AbstractApplicationContext (org.springframework.context.support)
refresh:737, SpringApplication (org.springframework.boot)
refreshContext:370, SpringApplication (org.springframework.boot)
run:314, SpringApplication (org.springframework.boot)
run:134, SpringApplicationBuilder (org.springframework.boot.builder)
main:24, Application (com.dtwave.dipper.resource.server.provider)
```



## Dubbo启动流程分析



spring.handlers文件

spring通过此文件找到dubbo自定义的xml schema的解析类实现。

```
http\://dubbo.apache.org/schema/dubbo=com.alibaba.dubbo.config.spring.schema.DubboNamespaceHandler
http\://code.alibabatech.com/schema/dubbo=com.alibaba.dubbo.config.spring.schema.DubboNamespaceHandler
```

DubboNamespaceHandler实现。改实现注册xsd模型的解析类，比如provider的解析类为DubboBeanDefinitionParser，具体的执行器为ProtocolConfig，其parse方法会把解析结果放到Bean的属性字段中。org.springframework.beans.factory.support.AbstractBeanDefinition#propertyValues。

```java
public class DubboNamespaceHandler extends NamespaceHandlerSupport {

    static {
        Version.checkDuplicate(DubboNamespaceHandler.class);
    }

    @Override
    public void init() {
        registerBeanDefinitionParser("application", new DubboBeanDefinitionParser(ApplicationConfig.class, true));
        registerBeanDefinitionParser("module", new DubboBeanDefinitionParser(ModuleConfig.class, true));
        registerBeanDefinitionParser("registry", new DubboBeanDefinitionParser(RegistryConfig.class, true));
        registerBeanDefinitionParser("monitor", new DubboBeanDefinitionParser(MonitorConfig.class, true));
        registerBeanDefinitionParser("provider", new DubboBeanDefinitionParser(ProviderConfig.class, true));
        registerBeanDefinitionParser("consumer", new DubboBeanDefinitionParser(ConsumerConfig.class, true));
        registerBeanDefinitionParser("protocol", new DubboBeanDefinitionParser(ProtocolConfig.class, true));
        registerBeanDefinitionParser("service", new DubboBeanDefinitionParser(ServiceBean.class, true));
        registerBeanDefinitionParser("reference", new DubboBeanDefinitionParser(ReferenceBean.class, false));
        registerBeanDefinitionParser("annotation", new AnnotationBeanDefinitionParser());
    }

}
```



DubboBeanDefinitionParser实现了spring的BeanDefinitionParser接口。





ServiceBean

在解析类中有个很重要的接口，实现了spring的InitializingBean、ApplicationContextAware、ApplicationEventPublisherAware、ApplicationListener等接口。



在此bean初始化完成后会调用







## 源码分析

### Dubbo SPI

以ServiceConfig.java类中的private static final Protocol protocol = ExtensionLoader.getExtensionLoader(Protocol.class).getAdaptiveExtension();入口做分析：

getExtentionLoader中有个缓存EXTENSION_LOADERS，没有则创建，缓存中的关键元素是ExtensionLoader。

重点在

getAdaptiveExtension->createAdaptiveExtension->



```java
    private static final ConcurrentMap<Class<?>, ExtensionLoader<?>> EXTENSION_LOADERS = new ConcurrentHashMap<Class<?>, ExtensionLoader<?>>();

public static <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
    if (type == null)
        throw new IllegalArgumentException("Extension type == null");
    if (!type.isInterface()) {
        throw new IllegalArgumentException("Extension type(" + type + ") is not interface!");
    }
    if (!withExtensionAnnotation(type)) {
        throw new IllegalArgumentException("Extension type(" + type +
                ") is not extension, because WITHOUT @" + SPI.class.getSimpleName() + " Annotation!");
    }

    ExtensionLoader<T> loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
    if (loader == null) {
        EXTENSION_LOADERS.putIfAbsent(type, new ExtensionLoader<T>(type));
        loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
    }
    return loader;
}
```



此步骤中会进行dubbo的ioc和类加载：

```java
private T createAdaptiveExtension() {
    try {
        return injectExtension((T) getAdaptiveExtensionClass().newInstance());
    } catch (Exception e) {
        throw new IllegalStateException("Can not create adaptive extension " + type + ", cause: " + e.getMessage(), e);
    }
}
```



getExtensionClasses步骤会进行类加载，此步骤会遍历读取META-INF中的接口配置文件，举例如下，dubbo spi拓展了Java spi的机制可以通过执行拓展名或者一些注解使能高级功能@Adaptive

```java
private Class<?> getAdaptiveExtensionClass() {
    getExtensionClasses();
    if (cachedAdaptiveClass != null) {
        return cachedAdaptiveClass;
    }
    return cachedAdaptiveClass = createAdaptiveExtensionClass();
}
```

参考文档：

http://dubbo.apache.org/zh-cn/docs/source_code_guide/dubbo-spi.html





### 自适应拓展

分为方法上注解@Adaptive和类上注解，方法注解上的逻辑较为复杂。而方法上的注解需要通过com.alibaba.dubbo.common.URL来获取对应的参数获取对应的拓展类。如果方法上有url类型参数可以直接取，如果没有则使用get方法取如果还是么有则报错。



文档参考：

http://dubbo.apache.org/zh-cn/docs/source_code_guide/adaptive-extension.html



### 服务导出



### 服务引入