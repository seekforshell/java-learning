

[toc]

# Dubbo源码解析



## 什么是Dubbo ?



![dubbo-architecture-roadmap](images/dubbo-architecture-roadmap.jpg)





## Dubbo的作用？





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

### SPI源码剖析

#### SPI基础原理

什么是SPI?

它的全称是Service Provider Interface，Dubbo SPI对Java SPI做了些改造，加入了一些定制化的功能。两者在配置文件和类加载上存在不同。

**配置文件的区别**

Java SPI，比如文件名org.springframework.boot.autoconfigure.EnableAutoConfiguration

```properties
com.alibaba.boot.dubbo.actuate.autoconfigure.DubboEndpointAutoConfiguration
com.alibaba.boot.dubbo.actuate.autoconfigure.DubboHealthIndicatorAutoConfiguration
```

java的类加载以java.util.ServiceLoader为主要实现，如果第三需要自定义资源文件，可以自定义serviceload实现

Dubbo SPI，将配置文件设计成键值对的方式，让用户可根据键名去选择相应的实现类：

ExtensionLoader对应于ServiceLoader的对应实现。

dubbo-2.6.6.jar!/META-INF/dubbo/internal/com.alibaba.dubbo.cache.CacheFactory

```properties
threadlocal=com.alibaba.dubbo.cache.support.threadlocal.ThreadLocalCacheFactory
lru=com.alibaba.dubbo.cache.support.lru.LruCacheFactory
jcache=com.alibaba.dubbo.cache.support.jcache.JCacheFactory
```



#### 源码解析

以ExtensionLoader.getExtensionLoader(Protocol.class) 入口做分析。

##### stack-1

```java
    private static final ConcurrentMap<Class<?>, ExtensionLoader<?>> EXTENSION_LOADERS = new ConcurrentHashMap<Class<?>, ExtensionLoader<?>>();

public static <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
    if (type == null)
        throw new IllegalArgumentException("Extension type == null");
    if (!type.isInterface()) {
        throw new IllegalArgumentException("Extension type(" + type + ") is not interface!");
    }
    // 接口必须是SPI注解的，否则报错
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



##### stack-2

ExtensionLoader#getAdaptiveExtension



```java
public T getAdaptiveExtension() {
    Object instance = cachedAdaptiveInstance.get();
  	// 先查缓存，没有则创建
    if (instance == null) {
        if (createAdaptiveInstanceError == null) {
            synchronized (cachedAdaptiveInstance) {
                instance = cachedAdaptiveInstance.get();
                if (instance == null) {
                    try {
                      	// 创建自适应接口的实现类示例
                        instance = createAdaptiveExtension();
                        cachedAdaptiveInstance.set(instance);
                    } catch (Throwable t) {
                        createAdaptiveInstanceError = t;
                        throw new IllegalStateException("fail to create adaptive instance: " + t.toString(), t);
                    }
                }
            }
        } else {
            throw new IllegalStateException("fail to create adaptive instance: " + createAdaptiveInstanceError.toString(), createAdaptiveInstanceError);
        }
    }

    return (T) instance;
}
```

此步骤中会进行dubbo的ioc和类加载：

```java
private T createAdaptiveExtension() {
    try {
      	// 注入示例
        return injectExtension((T) getAdaptiveExtensionClass().newInstance());
    } catch (Exception e) {
        throw new IllegalStateException("Can not create adaptive extension " + type + ", cause: " + e.getMessage(), e);
    }
}
```

##### stack-3

getAdaptiveExtensionClass

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

##### stack-4

getExtensionClasses

```java
private Map<String, Class<?>> getExtensionClasses() {
    Map<String, Class<?>> classes = cachedClasses.get();
    if (classes == null) {
        synchronized (cachedClasses) {
            classes = cachedClasses.get();
            if (classes == null) {
              	// 记载候选类
                classes = loadExtensionClasses();
                cachedClasses.set(classes);
            }
        }
    }
    return classes;
}
```

##### stack-4-2

动态生成类，步骤适用于没有找到@Adaptive注解的类的情况

```java
private Class<?> createAdaptiveExtensionClass() {
    // 构造源代码
    String code = createAdaptiveExtensionClassCode();
    ClassLoader classLoader = findClassLoader();
  	// 构造源码编译器
    com.alibaba.dubbo.common.compiler.Compiler compiler = ExtensionLoader.getExtensionLoader(com.alibaba.dubbo.common.compiler.Compiler.class).getAdaptiveExtension();
  	// 编译，返回
    return compiler.compile(code, classLoader);
}
```

创建字节码

createAdaptiveExtensionClassCode，这里结合具体的示例来理解更清楚，比如ProxyFactory接口的类生成。

```java
@SPI("javassist")
public interface ProxyFactory {

    /**
     * create proxy.
     *
     * @param invoker
     * @return proxy
     */
    @Adaptive({Constants.PROXY_KEY})
    <T> T getProxy(Invoker<T> invoker) throws RpcException;

    /**
     * create proxy.
     *
     * @param invoker
     * @return proxy
     */
    @Adaptive({Constants.PROXY_KEY})
    <T> T getProxy(Invoker<T> invoker, boolean generic) throws RpcException;

    /**
     * create invoker.
     *
     * @param <T>
     * @param proxy
     * @param type
     * @param url
     * @return invoker
     */
    @Adaptive({Constants.PROXY_KEY})
    <T> Invoker<T> getInvoker(T proxy, Class<T> type, URL url) throws RpcException;

}


```

解析：

```java
private String createAdaptiveExtensionClassCode() {
    StringBuilder codeBuilder = new StringBuilder();
    Method[] methods = type.getMethods();
    boolean hasAdaptiveAnnotation = false;
  	// 是否有@Adaptive注解的方法
    for (Method m : methods) {
        if (m.isAnnotationPresent(Adaptive.class)) {
            hasAdaptiveAnnotation = true;
            break;
        }
    }
    // no need to generate adaptive class since there's no adaptive method found.
    if (!hasAdaptiveAnnotation)
        throw new IllegalStateException("No adaptive method on extension " + type.getName() + ", refuse to create the adaptive class!");
		// 添加头文件等信息
    codeBuilder.append("package ").append(type.getPackage().getName()).append(";");
    codeBuilder.append("\nimport ").append(ExtensionLoader.class.getName()).append(";");
    codeBuilder.append("\npublic class ").append(type.getSimpleName()).append("$Adaptive").append(" implements ").append(type.getCanonicalName()).append(" {");

    for (Method method : methods) {
        Class<?> rt = method.getReturnType();
      	//方法参数类型
        Class<?>[] pts = method.getParameterTypes();
        Class<?>[] ets = method.getExceptionTypes();

        Adaptive adaptiveAnnotation = method.getAnnotation(Adaptive.class);
        StringBuilder code = new StringBuilder(512);
        if (adaptiveAnnotation == null) {
            code.append("throw new UnsupportedOperationException(\"method ")
                    .append(method.toString()).append(" of interface ")
                    .append(type.getName()).append(" is not adaptive method!\");");
        } else {
            int urlTypeIndex = -1;
            for (int i = 0; i < pts.length; ++i) {
                if (pts[i].equals(URL.class)) {
                    urlTypeIndex = i;
                    break;
                }
            }
            // found parameter in URL type
            if (urlTypeIndex != -1) {
                // Null Point check
                String s = String.format("\nif (arg%d == null) throw new IllegalArgumentException(\"url == null\");",
                        urlTypeIndex);
                code.append(s);

                s = String.format("\n%s url = arg%d;", URL.class.getName(), urlTypeIndex);
                code.append(s);
            }
            // did not find parameter in URL type
            else {
                String attribMethod = null;

                // find URL getter method
                LBL_PTS:
                for (int i = 0; i < pts.length; ++i) {
                  	// 获取入参类型的类类型的所有方法，比如Invoker接口的所有方法中是否有get方法并且
                    // 返回值为URL的
                    Method[] ms = pts[i].getMethods();
                    for (Method m : ms) {
                        String name = m.getName();
                        if ((name.startsWith("get") || name.length() > 3)
                                && Modifier.isPublic(m.getModifiers())
                                && !Modifier.isStatic(m.getModifiers())
                                && m.getParameterTypes().length == 0
                                && m.getReturnType() == URL.class) {
                            urlTypeIndex = i;
                            attribMethod = name;
                            break LBL_PTS;
                        }
                    }
                }
                if (attribMethod == null) {
                    throw new IllegalStateException("fail to create adaptive class for interface " + type.getName()
                            + ": not found url parameter or url attribute in parameters of method " + method.getName());
                }

                // Null point check
                String s = String.format("\nif (arg%d == null) throw new IllegalArgumentException(\"%s argument == null\");",
                        urlTypeIndex, pts[urlTypeIndex].getName());
                code.append(s);
                s = String.format("\nif (arg%d.%s() == null) throw new IllegalArgumentException(\"%s argument %s() == null\");",
                        urlTypeIndex, attribMethod, pts[urlTypeIndex].getName(), attribMethod);
                code.append(s);

                s = String.format("%s url = arg%d.%s();", URL.class.getName(), urlTypeIndex, attribMethod);
                code.append(s);
            }

            String[] value = adaptiveAnnotation.value();
            // value is not set, use the value generated from class name as the key
            if (value.length == 0) {
                char[] charArray = type.getSimpleName().toCharArray();
                StringBuilder sb = new StringBuilder(128);
                for (int i = 0; i < charArray.length; i++) {
                    if (Character.isUpperCase(charArray[i])) {
                        if (i != 0) {
                            sb.append(".");
                        }
                        sb.append(Character.toLowerCase(charArray[i]));
                    } else {
                        sb.append(charArray[i]);
                    }
                }
                value = new String[]{sb.toString()};
            }

            boolean hasInvocation = false;
            for (int i = 0; i < pts.length; ++i) {
                if (pts[i].getName().equals("com.alibaba.dubbo.rpc.Invocation")) {
                    // Null Point check
                    String s = String.format("\nif (arg%d == null) throw new IllegalArgumentException(\"invocation == null\");", i);
                    code.append(s);
                    s = String.format("\nString methodName = arg%d.getMethodName();", i);
                    code.append(s);
                    hasInvocation = true;
                    break;
                }
            }

            String defaultExtName = cachedDefaultName;
            String getNameCode = null;
            for (int i = value.length - 1; i >= 0; --i) {
                if (i == value.length - 1) {
                    if (null != defaultExtName) {
                        if (!"protocol".equals(value[i]))
                            if (hasInvocation)
                                getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                            else
                                getNameCode = String.format("url.getParameter(\"%s\", \"%s\")", value[i], defaultExtName);
                        else
                            getNameCode = String.format("( url.getProtocol() == null ? \"%s\" : url.getProtocol() )", defaultExtName);
                    } else {
                        if (!"protocol".equals(value[i]))
                            if (hasInvocation)
                                getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                            else
                                getNameCode = String.format("url.getParameter(\"%s\")", value[i]);
                        else
                            getNameCode = "url.getProtocol()";
                    }
                } else {
                    if (!"protocol".equals(value[i]))
                        if (hasInvocation)
                            getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                        else
                            getNameCode = String.format("url.getParameter(\"%s\", %s)", value[i], getNameCode);
                    else
                        getNameCode = String.format("url.getProtocol() == null ? (%s) : url.getProtocol()", getNameCode);
                }
            }
            code.append("\nString extName = ").append(getNameCode).append(";");
            // check extName == null?
            String s = String.format("\nif(extName == null) " +
                            "throw new IllegalStateException(\"Fail to get extension(%s) name from url(\" + url.toString() + \") use keys(%s)\");",
                    type.getName(), Arrays.toString(value));
            code.append(s);

            s = String.format("\n%s extension = (%<s)%s.getExtensionLoader(%s.class).getExtension(extName);",
                    type.getName(), ExtensionLoader.class.getSimpleName(), type.getName());
            code.append(s);

            // return statement
            if (!rt.equals(void.class)) {
                code.append("\nreturn ");
            }

            s = String.format("extension.%s(", method.getName());
            code.append(s);
            for (int i = 0; i < pts.length; i++) {
                if (i != 0)
                    code.append(", ");
                code.append("arg").append(i);
            }
            code.append(");");
        }

        codeBuilder.append("\npublic ").append(rt.getCanonicalName()).append(" ").append(method.getName()).append("(");
        for (int i = 0; i < pts.length; i++) {
            if (i > 0) {
                codeBuilder.append(", ");
            }
            codeBuilder.append(pts[i].getCanonicalName());
            codeBuilder.append(" ");
            codeBuilder.append("arg").append(i);
        }
        codeBuilder.append(")");
        if (ets.length > 0) {
            codeBuilder.append(" throws ");
            for (int i = 0; i < ets.length; i++) {
                if (i > 0) {
                    codeBuilder.append(", ");
                }
                codeBuilder.append(ets[i].getCanonicalName());
            }
        }
        codeBuilder.append(" {");
        codeBuilder.append(code.toString());
        codeBuilder.append("\n}");
    }
    codeBuilder.append("\n}");
    if (logger.isDebugEnabled()) {
        logger.debug(codeBuilder.toString());
    }
    return codeBuilder.toString();
}
```

ProxyFactory生成后的源代码

com.alibaba.dubbo.common.URL，dubbo根据URL里的参数来进行自适应类的匹配，我想这样跟URL本省的含义相匹配。

```java
public String getParameter(String key, String defaultValue) {
    String value = getParameter(key);
    if (value == null || value.length() == 0) {
        return defaultValue;
    }
    return value;
}
```

ProxyFactory$Adaptive是生成的自适应的源代码

ProxyFactory的实现原理是根据invoker参数配置，通过extentionloader加载机制来查找相应的实现类

```java
package com.alibaba.dubbo.rpc;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
public class ProxyFactory$Adaptive implements com.alibaba.dubbo.rpc.ProxyFactory {
  
    public java.lang.Object getProxy(com.alibaba.dubbo.rpc.Invoker arg0) throws com.alibaba.dubbo.rpc.RpcException {
        if (arg0 == null) throw new IllegalArgumentException("com.alibaba.dubbo.rpc.Invoker argument == null");
        if (arg0.getUrl() == null) throw new IllegalArgumentException("com.alibaba.dubbo.rpc.Invoker argument getUrl() == null");com.alibaba.dubbo.common.URL url = arg0.getUrl();
      	// 拓展类获取，第一个为key，第二个为默认名，如果invoker参数中没有携带proxy参数那么取默认值javassist
        String extName = url.getParameter("proxy", "javassist");
        if(extName == null) throw new IllegalStateException("Fail to get extension(com.alibaba.dubbo.rpc.ProxyFactory) name from url(" + url.toString() + ") use keys([proxy])");
      	// 通过extensionLoader加载类
        com.alibaba.dubbo.rpc.ProxyFactory extension = (com.alibaba.dubbo.rpc.ProxyFactory)ExtensionLoader.getExtensionLoader(com.alibaba.dubbo.rpc.ProxyFactory.class).getExtension(extName);
        return extension.getProxy(arg0);
    }
  
    public java.lang.Object getProxy(com.alibaba.dubbo.rpc.Invoker arg0, boolean arg1) throws com.alibaba.dubbo.rpc.RpcException {
        if (arg0 == null) throw new IllegalArgumentException("com.alibaba.dubbo.rpc.Invoker argument == null");
        if (arg0.getUrl() == null) throw new IllegalArgumentException("com.alibaba.dubbo.rpc.Invoker argument getUrl() == null");com.alibaba.dubbo.common.URL url = arg0.getUrl();
        String extName = url.getParameter("proxy", "javassist");
        if(extName == null) throw new IllegalStateException("Fail to get extension(com.alibaba.dubbo.rpc.ProxyFactory) name from url(" + url.toString() + ") use keys([proxy])");
        com.alibaba.dubbo.rpc.ProxyFactory extension = (com.alibaba.dubbo.rpc.ProxyFactory)ExtensionLoader.getExtensionLoader(com.alibaba.dubbo.rpc.ProxyFactory.class).getExtension(extName);
        return extension.getProxy(arg0, arg1);
    }
  	
    public com.alibaba.dubbo.rpc.Invoker getInvoker(java.lang.Object arg0, java.lang.Class arg1, com.alibaba.dubbo.common.URL arg2) throws com.alibaba.dubbo.rpc.RpcException {
        if (arg2 == null) throw new IllegalArgumentException("url == null");
        com.alibaba.dubbo.common.URL url = arg2;
        String extName = url.getParameter("proxy", "javassist");
        if(extName == null) throw new IllegalStateException("Fail to get extension(com.alibaba.dubbo.rpc.ProxyFactory) name from url(" + url.toString() + ") use keys([proxy])");
        com.alibaba.dubbo.rpc.ProxyFactory extension = (com.alibaba.dubbo.rpc.ProxyFactory)ExtensionLoader.getExtensionLoader(com.alibaba.dubbo.rpc.ProxyFactory.class).getExtension(extName);
        return extension.getInvoker(arg0, arg1, arg2);
    }
}
```

##### stack-5

loadExtensionClasses

```java
private Map<String, Class<?>> loadExtensionClasses() {
    final SPI defaultAnnotation = type.getAnnotation(SPI.class);
    if (defaultAnnotation != null) {
        String value = defaultAnnotation.value();
        if ((value = value.trim()).length() > 0) {
            String[] names = NAME_SEPARATOR.split(value);
            if (names.length > 1) {
                throw new IllegalStateException("more than 1 default extension name on extension " + type.getName()
                        + ": " + Arrays.toString(names));
            }
            if (names.length == 1) cachedDefaultName = names[0];
        }
    }

    // 从指定目录中加载自适应类实现
    Map<String, Class<?>> extensionClasses = new HashMap<String, Class<?>>();
    loadDirectory(extensionClasses, DUBBO_INTERNAL_DIRECTORY);
    loadDirectory(extensionClasses, DUBBO_DIRECTORY);
    loadDirectory(extensionClasses, SERVICES_DIRECTORY);
    return extensionClasses;
}
```



##### stack-6

```java
private void loadDirectory(Map<String, Class<?>> extensionClasses, String dir) {
    // 找到文件名
    String fileName = dir + type.getName();
    try {
        Enumeration<java.net.URL> urls;
        ClassLoader classLoader = findClassLoader();
        if (classLoader != null) {
            urls = classLoader.getResources(fileName);
        } else {
            urls = ClassLoader.getSystemResources(fileName);
        }
        if (urls != null) {
            while (urls.hasMoreElements()) {
                java.net.URL resourceURL = urls.nextElement();
              	// 加载拓展类
                loadResource(extensionClasses, classLoader, resourceURL);
            }
        }
    } catch (Throwable t) {
        logger.error("Exception when load extension class(interface: " +
                type + ", description file: " + fileName + ").", t);
    }
}
```

##### stack-7



```java
private void loadResource(Map<String, Class<?>> extensionClasses, ClassLoader classLoader, java.net.URL resourceURL) {
    try {
      	// 读取文件
        BufferedReader reader = new BufferedReader(new InputStreamReader(resourceURL.openStream(), "utf-8"));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                final int ci = line.indexOf('#');
                if (ci >= 0) line = line.substring(0, ci);
                line = line.trim();
                if (line.length() > 0) {
                    try {
                      	// dubbo spi 键值对获取
                        String name = null;
                        int i = line.indexOf('=');
                        if (i > 0) {
                            name = line.substring(0, i).trim();
                            line = line.substring(i + 1).trim();
                        }
                        if (line.length() > 0) {
                          	// 核心加载流程
                            loadClass(extensionClasses, resourceURL, Class.forName(line, true, classLoader), name);
                        }
                    } catch (Throwable t) {
                        IllegalStateException e = new IllegalStateException("Failed to load extension class(interface: " + type + ", class line: " + line + ") in " + resourceURL + ", cause: " + t.getMessage(), t);
                        exceptions.put(line, e);
                    }
                }
            }
        } finally {
            reader.close();
        }
    } catch (Throwable t) {
        logger.error("Exception when load extension class(interface: " +
                type + ", class file: " + resourceURL + ") in " + resourceURL, t);
    }
}
```



stack-8

loadClass

```java
private void loadClass(Map<String, Class<?>> extensionClasses, java.net.URL resourceURL, Class<?> clazz, String name) throws NoSuchMethodException {
    if (!type.isAssignableFrom(clazz)) {
        throw new IllegalStateException("Error when load extension class(interface: " +
                type + ", class line: " + clazz.getName() + "), class "
                + clazz.getName() + "is not subtype of interface.");
    }
  	// 如果是自适应注解在类上比较简单直接返回,就不用走createAdaptiveExtensionClass流程
    if (clazz.isAnnotationPresent(Adaptive.class)) {
        if (cachedAdaptiveClass == null) {
            cachedAdaptiveClass = clazz;
        } else if (!cachedAdaptiveClass.equals(clazz)) {
            throw new IllegalStateException("More than 1 adaptive class found: "
                    + cachedAdaptiveClass.getClass().getName()
                    + ", " + clazz.getClass().getName());
        }
    // 查看构造体是否为typ的包装类
    } else if (isWrapperClass(clazz)) {
        Set<Class<?>> wrappers = cachedWrapperClasses;
        if (wrappers == null) {
            cachedWrapperClasses = new ConcurrentHashSet<Class<?>>();
            wrappers = cachedWrapperClasses;
        }
        wrappers.add(clazz);
    } else {
        clazz.getConstructor();
      	// 如果配置文件中的键名为空，则查看此类是否被Extension注解
        if (name == null || name.length() == 0) {
            name = findAnnotationName(clazz);
            if (name.length() == 0) {
                throw new IllegalStateException("No such extension name for the class " + clazz.getName() + " in the config " + resourceURL);
            }
        }
        String[] names = NAME_SEPARATOR.split(name);
        if (names != null && names.length > 0) {
            Activate activate = clazz.getAnnotation(Activate.class);
            if (activate != null) {
                cachedActivates.put(names[0], activate);
            }
            for (String n : names) {
                if (!cachedNames.containsKey(clazz)) {
                    cachedNames.put(clazz, n);
                }
                Class<?> c = extensionClasses.get(n);
                if (c == null) {
                    extensionClasses.put(n, clazz);
                } else if (c != clazz) {
                    throw new IllegalStateException("Duplicate extension " + type.getName() + " name " + n + " on " + c.getName() + " and " + clazz.getName());
                }
            }
        }
    }
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

