package design.proxy;

import java.util.Map;

public class Consumer implements ConsumterInterface {
    public void purchase(Map<String, Object> good) {
        System.out.println(String.format("purchase a computer:%s", good.toString()));
    }
}
