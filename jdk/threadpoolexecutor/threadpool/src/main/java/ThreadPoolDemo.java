import java.util.concurrent.*;

public class ThreadPoolDemo {

    public static void main(String[] args) {
        BlockingQueue blockingQueue = new LinkedBlockingQueue();
        int core = Runtime.getRuntime().availableProcessors();
        ExecutorService servicePool = new ThreadPoolExecutor(core/2 + 1, core, 1, TimeUnit.MINUTES, blockingQueue);
        ThreadTask task = new ThreadTask();
        for (int i = 0; i < 100; i++) {
            servicePool.submit(task);
        }
        try {
            servicePool.shutdown();
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            servicePool.awaitTermination(60, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            servicePool.shutdownNow();
        }
    }
}
