package org.example;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Fully working custom Thread Pool implementation (single file, interview-grade)
 * Covers:
 * - Fixed worker threads
 * - Bounded blocking queue (Lock/Condition)
 * - submit(Callable), submit(Runnable)
 * - execute(Runnable)
 * - Rejection policies
 * - Simple Future implementation
 * - Graceful + immediate shutdown
 * - awaitTermination
 */
public class CustomThreadPool {

    // --------- Rejection Policy Interfaces and Common Implementations ---------

    public interface RejectionPolicy {
        void reject(Runnable task, CustomThreadPool pool);
    }

    public static class AbortPolicy implements RejectionPolicy {
        @Override
        public void reject(Runnable task, CustomThreadPool pool) {
            throw new RejectedExecutionException("Task rejected: " + task);
        }
    }

    public static class CallerRunsPolicy implements RejectionPolicy {
        @Override
        public void reject(Runnable task, CustomThreadPool pool) {
            task.run();
        }
    }

    public static class DiscardPolicy implements RejectionPolicy {
        @Override
        public void reject(Runnable task, CustomThreadPool pool) {
            // silently ignore
        }
    }

    public static class DiscardOldestPolicy implements RejectionPolicy {
        @Override
        public void reject(Runnable task, CustomThreadPool pool) {
            pool.taskQueue.poll();
            pool.taskQueue.offer(task);
        }
    }

    public static class RejectedExecutionException extends RuntimeException {
        public RejectedExecutionException(String msg) {
            super(msg);
        }
    }

    // --------- Simple Future Implementation ---------

    public interface Future<V> {
        boolean cancel(boolean mayInterruptIfRunning);

        boolean isCancelled();

        boolean isDone();

        V get() throws InterruptedException;

        V get(long timeout, TimeUnit unit) throws InterruptedException;
    }

    public static class SimpleFuture<V> implements Future<V> {
        private V result;
        private Throwable exception;
        private boolean done = false;
        private boolean cancelled = false;

        @Override
        public synchronized boolean cancel(boolean mayInterruptIfRunning) {
            if (done) return false;
            cancelled = true;
            done = true;
            notifyAll();
            return true;
        }

        @Override
        public synchronized boolean isCancelled() {
            return cancelled;
        }

        @Override
        public synchronized boolean isDone() {
            return done;
        }

        protected synchronized void setResult(V res) {
            if (done) return;
            this.result = res;
            this.done = true;
            notifyAll();
        }

        protected synchronized void setException(Throwable t) {
            if (done) return;
            this.exception = t;
            this.done = true;
            notifyAll();
        }

        @Override
        public synchronized V get() throws InterruptedException {
            while (!done) wait();
            if (cancelled) throw new InterruptedException("Task cancelled");
            if (exception != null) throw new RuntimeException(exception);
            return result;
        }

        @Override
        public synchronized V get(long timeout, TimeUnit unit) throws InterruptedException {
            long ms = unit.toMillis(timeout);
            long deadline = System.currentTimeMillis() + ms;
            while (!done && System.currentTimeMillis() < deadline) {
                wait(ms);
            }
            if (!done) throw new InterruptedException("Timeout");
            if (cancelled) throw new InterruptedException("Task cancelled");
            if (exception != null) throw new RuntimeException(exception);
            return result;
        }
    }

    // --------- Bounded Blocking Queue using Lock/Condition ---------

    public static class BlockingQueue<T> {
        private final Deque<T> deque;
        private final int capacity;
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition notFull = lock.newCondition();
        private final Condition notEmpty = lock.newCondition();

        public BlockingQueue(int capacity) {
            this.capacity = capacity;
            this.deque = new ArrayDeque<>(capacity);
        }

        public void put(T item) throws InterruptedException {
            lock.lock();
            try {
                while (deque.size() == capacity) {
                    notFull.await();
                }
                deque.addLast(item);
                notEmpty.signal();
            } finally {
                lock.unlock();
            }
        }

        public T take() throws InterruptedException {
            lock.lock();
            try {
                while (deque.isEmpty()) {
                    notEmpty.await();
                }
                T item = deque.removeFirst();
                notFull.signal();
                return item;
            } finally {
                lock.unlock();
            }
        }

        public boolean offer(T item) {
            lock.lock();
            try {
                if (deque.size() == capacity) return false;
                deque.addLast(item);
                notEmpty.signal();
                return true;
            } finally {
                lock.unlock();
            }
        }

        public T poll() {
            lock.lock();
            try {
                if (deque.isEmpty()) return null;
                T item = deque.removeFirst();
                notFull.signal();
                return item;
            } finally {
                lock.unlock();
            }
        }

        public List<T> drainAll() {
            lock.lock();
            try {
                List<T> list = new ArrayList<>(deque);
                deque.clear();
                notFull.signalAll();
                return list;
            } finally {
                lock.unlock();
            }
        }

        public int size() {
            lock.lock();
            try {
                return deque.size();
            } finally {
                lock.unlock();
            }
        }
    }

    // --------- Task Wrapper (Runnable around Callable + Future) ---------

    private static class Task<V> implements Runnable {
        private final Callable<V> callable;
        private final SimpleFuture<V> future;

        public Task(Callable<V> callable, SimpleFuture<V> future) {
            this.callable = callable;
            this.future = future;
        }

        @Override
        public void run() {
            try {
                if (!future.isCancelled()) {
                    V res = callable.call();
                    future.setResult(res);
                }
            } catch (Throwable t) {
                future.setException(t);
            }
        }
    }

    // --------- Worker Threads ---------

    private class Worker extends Thread {
        private volatile boolean running = true;

        Worker(int id) {
            super("CustomPool-Worker-" + id);
        }

        @Override
        public void run() {
            while (running) {
                try {
                    Runnable task = taskQueue.take();
                    task.run();
                } catch (InterruptedException e) {
                    if (isStopped) break; // stop on shutdownNow
                } catch (Throwable t) {
                    t.printStackTrace();
                }

                if (isShutdown && taskQueue.size() == 0) break;
            }

            synchronized (CustomThreadPool.this) {
                workers.remove(this);
                if (workers.isEmpty()) CustomThreadPool.this.notifyAll();
            }
        }

        void stopWorker() {
            running = false;
            interrupt();
        }
    }

    // --------- Pool Core Fields ---------

    private final int poolSize;
    private final BlockingQueue<Runnable> taskQueue;
    private final Set<Worker> workers = new HashSet<>();
    private final RejectionPolicy rejectionPolicy;
    private volatile boolean isShutdown = false;
    private volatile boolean isStopped = false;

    // --------- Constructor ---------

    public CustomThreadPool(int poolSize, int queueCapacity, RejectionPolicy rejectionPolicy) {
        if (poolSize <= 0) throw new IllegalArgumentException("Pool size > 0");
        this.poolSize = poolSize;
        this.taskQueue = new BlockingQueue<>(queueCapacity);
        this.rejectionPolicy = rejectionPolicy == null ? new AbortPolicy() : rejectionPolicy;
        initWorkers();
    }

    private void initWorkers() {
        for (int i = 0; i < poolSize; i++) {
            Worker w = new Worker(i);
            workers.add(w);
            w.start();
        }
    }

    // --------- Public API ---------

    public void execute(Runnable task) {
        if (isShutdown) throw new RejectedExecutionException("Pool shutdown");
        boolean offered = taskQueue.offer(task);
        if (!offered) rejectionPolicy.reject(task, this);
    }

    public <V> Future<V> submit(Callable<V> callable) {
        if (isShutdown) throw new RejectedExecutionException("Pool shutdown");
        SimpleFuture<V> f = new SimpleFuture<>();
        Task<V> t = new Task<>(callable, f);
        boolean offered = taskQueue.offer(t);
        if (!offered) rejectionPolicy.reject(t, this);
        return f;
    }

    public Future<?> submit(Runnable runnable) {
        return submit(runnable, null);
    }

    public <V> Future<V> submit(Runnable runnable, V result) {
        Callable<V> callable = () -> {
            runnable.run();
            return result;
        };
        return submit(callable);
    }

    public void shutdown() {
        isShutdown = true;
        synchronized (this) {
            if (workers.isEmpty()) notifyAll();
        }
    }

    public List<Runnable> shutdownNow() {
        isShutdown = true;
        isStopped = true;
        for (Worker w : new ArrayList<>(workers)) {
            w.stopWorker();
        }
        List<Runnable> pending = taskQueue.drainAll();
        synchronized (this) {
            if (workers.isEmpty()) notifyAll();
        }
        return pending;
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long ms = unit.toMillis(timeout);
        long end = System.currentTimeMillis() + ms;
        synchronized (this) {
            while (!workers.isEmpty()) {
                long rem = end - System.currentTimeMillis();
                if (rem <= 0) return false;
                wait(rem);
            }
        }
        return true;
    }

    // Utility methods
    public int getQueueSize() { return taskQueue.size(); }

    public int getWorkerCount() {
        synchronized (this) {
            return workers.size();
        }
    }

    @Override
    public String toString() {
        return "CustomThreadPool{workers=" + getWorkerCount() + ", queued=" + getQueueSize() + "}";
    }

    // --------- Demo Main Method ---------

    public static void main(String[] args) throws Exception {
        System.out.println("Starting CustomThreadPool demo...\n");

        CustomThreadPool pool = new CustomThreadPool(3, 5, new CallerRunsPolicy());

        // Submit 8 tasks
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            int id = i;
            futures.add(pool.submit(() -> {
                System.out.println(Thread.currentThread().getName() + " executing task " + id);
                Thread.sleep(500);
                return id * id;
            }));
        }

        // One fire-and-forget
        pool.execute(() -> System.out.println("Fire-and-forget executed by " + Thread.currentThread().getName()));

        // Collect results
        for (int i = 0; i < futures.size(); i++) {
            System.out.println("Result of task " + i + " = " + futures.get(i).get());
        }

        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
        System.out.println("\nGraceful shutdown complete.\n");
    }
}
