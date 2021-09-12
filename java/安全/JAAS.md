# JAAS

## 什么是JAAS?

JAAS(Java Authentication and Authorization Service)，Jaas主要用作以下两种用途：

a.认证用于，可靠、安全的认证谁可以执行程序/servlet,bean

b.授权。判断认证用户是否有正确的权限来执行相应的动作



## 核心类说明

### Subject

Subject主要包括以下字段，principals指的是用户的唯一表示，可以是用户名或者Principal等。crediential是证书，包括私有和公共证书。

```java
public Subject() {

    this.principals = Collections.synchronizedSet
                    (new SecureSet<Principal>(this, PRINCIPAL_SET));
    this.pubCredentials = Collections.synchronizedSet
                    (new SecureSet<Object>(this, PUB_CREDENTIAL_SET));
    this.privCredentials = Collections.synchronizedSet
                    (new SecureSet<Object>(this, PRIV_CREDENTIAL_SET));
}
```

### Authentication Classes and Interfaces



#### LoginContext





#### LoginModule

接口实现：

```

public interface LoginModule {
    void initialize(Subject subject, CallbackHandler callbackHandler,
                    Map<String,?> sharedState,
                    Map<String,?> options);

    boolean login() throws LoginException;

    boolean commit() throws LoginException;

    boolean abort() throws LoginException;

    boolean logout() throws LoginException;
}
```

常见的实现类

```
Krb5LoginModule
```





## Flink如何实现安全认证的？



