package design.proxy;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

public class ProxyApp {
    public static void main(String[] args) {
        Map<String, Object> good = new HashMap<String, Object>();
        good.put("name", "computer");
        good.put("price", 8000);
        good.put("branch", "apple");

        System.getProperties().setProperty("sun.misc.ProxyGenerator.saveGeneratedFiles", "true");

        // 委托对象
        ConsumterInterface consumer = new Consumer();

        // 在此步骤中，可以在调用方法前后做一些额外操作
        InvocationHandler handler = new ProxyHandler(consumer);

        // 代理对象
        ConsumterInterface proxyConsumer = (ConsumterInterface) Proxy.newProxyInstance(consumer.getClass().getClassLoader(),
                consumer.getClass().getInterfaces(), handler);

        proxyConsumer.purchase(null);
    }
}
