package org.example;

public class Main {
    public static void main(String[] args) {
        // Create a pool with 3 worker threads
        CustomThreadPool pool = new CustomThreadPool(3, 100, null);

        // Submit Runnable tasks
        for (int i = 1; i <= 5; i++) {
            int id = i;
            pool.submit(() -> {
                System.out.println("Running Runnable Task " + id + " on " + Thread.currentThread().getName());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        // Submit Callable tasks that return results
        for (int i = 1; i <= 5; i++) {
            int id = i;
            // NOTE: use CustomThreadPool.Future here, not SimpleFuture
            CustomThreadPool.Future<String> future = pool.submit(() -> {
                Thread.sleep(500);
                return "Result from Callable Task " + id + " on " + Thread.currentThread().getName();
            });

            // Fetch result in a separate thread
            new Thread(() -> {
                try {
                    System.out.println("Got: " + future.get());
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();
        }

        // Let tasks complete
        try {
            Thread.sleep(4000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Gracefully shutdown the pool
        pool.shutdown();
        System.out.println("Thread pool shut down gracefully.");
    }
}
