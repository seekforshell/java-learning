package design.proxy;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

/**
 * 动态代理：代理处理逻辑
 */
public class ProxyHandler implements InvocationHandler {
    // 被代理的对象
    private Object object;

    ProxyHandler(Object object) {
        this.object = object;
    }

    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        before();

        Object result = method.invoke(this.object, args);

        after();

        return result;
    }

    private void before() {
        System.out.println("=== before");
    }

    private void after() {
        System.out.println("=== after");
    }
}
