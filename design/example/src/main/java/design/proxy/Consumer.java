package design.proxy;

import java.util.Map;

public class Consumer implements ConsumterInterface {
    public void purchase() {
        System.out.println(String.format("purchase a computer"));
    }
}
