# Spring 修炼手册

## Spring 框架

spring framework包含哪些内容？

```
At the heart are the modules of the core container, including a configuration model and a dependency injection mechanism. Beyond that, the Spring Framework provides foundational support for different application architectures, including messaging, transactional data and persistence, and web. It also includes the Servlet-based Spring MVC web framework and, in parallel, the Spring WebFlux reactive web framework.
```



### IoC容器



IoC也叫做DI。也就是说控制反转和依赖注入可以视为同一个概念。

对象通过多种方式（注解、构造体）定义依赖的对象，Spring在启动过程中根据bean的定义利用java反射等技术将其所依赖的bean注入到容器中；这中实例化过程跟直接通过构造体构造对象是相反的。比如我们需要对象A（对象A需要依赖对象B），我们需要先创建对象B才能够得到对象A的完整初始化；而依赖注入的方式跟此相反，容器会先创建对象A放入到容器中，然后再根据其依赖进行注入。

#### 依赖注入的方式



##### xml形式



##### 基于注解

###### `@Autowired`

###### `@Configuration`和`@Primary`

```java
@Configuration
public class MovieConfiguration {

    @Bean
    @Primary
    public MovieCatalog firstMovieCatalog() { ... }

    @Bean
    public MovieCatalog secondMovieCatalog() { ... }

    // ...
}
```



相当于基于配置文件的如下形式：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<beans xmlns="http://www.springframework.org/schema/beans"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xmlns:context="http://www.springframework.org/schema/context"
    xsi:schemaLocation="http://www.springframework.org/schema/beans
        https://www.springframework.org/schema/beans/spring-beans.xsd
        http://www.springframework.org/schema/context
        https://www.springframework.org/schema/context/spring-context.xsd">

    <context:annotation-config/>

    <bean class="example.SimpleMovieCatalog" primary="true">
        <!-- inject any dependencies required by this bean -->
    </bean>

    <bean class="example.SimpleMovieCatalog">
        <!-- inject any dependencies required by this bean -->
    </bean>

    <bean id="movieRecommender" class="example.MovieRecommender"/>

</beans>
```



###### `@PostConstruct`



###### `@PreDestroy`



##### 自动扫描



容器可以扫描相关的包路径需要需要被注入的bean，扫描路径的配置可以使用@MapperScan或者**@ComponentScan**



```
@Configuration
@ComponentScan(basePackages = "org.example")
public class AppConfig  {
    // ...
}
```

当然扫描也可以添加过滤器进行过滤，过滤类型如下：



| Filter Type          | Example Expression           | Description                                                  |
| :------------------- | :--------------------------- | :----------------------------------------------------------- |
| annotation (default) | `org.example.SomeAnnotation` | An annotation to be *present* or *meta-present* at the type level in target components. |
| assignable           | `org.example.SomeClass`      | A class (or interface) that the target components are assignable to (extend or implement). |
| aspectj              | `org.example..*Service+`     | An AspectJ type expression to be matched by the target components. |
| regex                | `org\.example\.Default.*`    | A regex expression to be matched by the target components' class names. |
| custom               | `org.example.MyTypeFilter`   | A custom implementation of the `org.springframework.core.type.TypeFilter` interface. |

note

```shell

如果每找一个bean都需要扫描所有的包及路径将会很耗时间，所以指定包的扫描路径不失为一种好方法。


```



#### 容器



#### Bean



## 常用注解



### @Mapper和@Repository

#### 相同点

两个都是注解在Dao上

#### 不同

@Repository需要在Spring中配置扫描地址，然后生成Dao层的Bean才能被注入到Service层中。

@Mapper不需要配置扫描地址，通过xml里面的namespace里面的接口地址，生成了Bean后注入到Service层中。

##常见问题##

###什么情况下事务失效？###
https://www.cnblogs.com/heqiyoujing/p/11221093.html

