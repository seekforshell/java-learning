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

