import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

public class ThreadTask implements Runnable {
    AtomicInteger num = new AtomicInteger();

    public void run() {
        try {
            System.out.println(String.format("%d:%s", num.incrementAndGet(), System.currentTimeMillis()));
            // 当系统IO是可能会对中断进行处理
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
