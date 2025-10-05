package org.example;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

interface BoundedBuffer<T> {
    /**
     * Put an item into the buffer
     * BLOCKS if buffer is full until space becomes available
     */
    void put(T item) throws InterruptedException;

    /**
     * Take an item from the buffer
     * BLOCKS if buffer is empty until item becomes available
     */
    T take() throws InterruptedException;

    /**
     * Non-blocking put - returns true if successful, false if buffer full
     */
    boolean offer(T item) throws InterruptedException;

    /**
     * Get current size (thread-safe)
     */
    int size() throws InterruptedException;

    /**
     * Check if buffer is empty
     */
    boolean isEmpty();

    /**
     * Check if buffer is full
     */
    boolean isFull();
}


class Bounded_Buffer_Synchronized<T> implements BoundedBuffer<T>{
    private final Queue<T> q;
    private final int capacity;

    public Bounded_Buffer_Synchronized(int capacity) {
        this.capacity = capacity;
        q = new LinkedList<T>();
    }

    @Override
    public synchronized void put(T item) throws InterruptedException {
        while(isFull()) wait();

        q.offer(item);
        System.out.println(Thread.currentThread().getName() + " PUT: " + item + " | Size: " + q.size());
        // Notify waiting consumers that an item is available
        notifyAll();
    }

    @Override
    public synchronized T take() throws InterruptedException {
        while(isEmpty()) wait();

        T item = q.poll();
        System.out.println(Thread.currentThread().getName() + " TAKE: " + item + " | Size: " + q.size());

        // Notify waiting producers that space is available
        notifyAll();

        return item;
    }

    @Override
    public synchronized boolean offer(T item) {
        if(isFull())  {
            return false;
        }
        q.offer(item);
        System.out.println(Thread.currentThread().getName() + " OFFER: " + item + " | Size: " + q.size());
        notifyAll();
        return true;
    }

    @Override
    public synchronized int size() {
        return q.size();
    }

    @Override
    public synchronized boolean isEmpty() {
        return q.isEmpty();
    }

    @Override
    public synchronized boolean isFull() {
        return q.size() == capacity;
    }
}

class Bounded_Buffer_Locks<T> implements BoundedBuffer<T> {
    private final ReentrantLock lock;
    private final Queue<T> q;
    private final int capacity;
    private Condition notFull;
    private Condition notEmpty;

    public Bounded_Buffer_Locks(int capacity) {
        this.capacity = capacity;
        this.lock = new ReentrantLock();
        q = new LinkedList<>();
        notEmpty = lock.newCondition();
        notFull = lock.newCondition();
    }

    @Override
    public void put(T item) throws InterruptedException {
        lock.lock();
        try {
            while (q.size() == capacity) {
                notFull.await();
            }

            q.offer(item);
            System.out.println(Thread.currentThread().getName() + " PUT: " + item + " | Size: " + q.size());
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public T take() throws InterruptedException {
        lock.lock();
        try {
            while (q.isEmpty()) {
                notEmpty.await();
            }

            T item = q.poll();
            System.out.println(Thread.currentThread().getName() + " TAKE: " + item + " | Size: " + q.size());
            notFull.signal();
            return item;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean offer(T item) {
        lock.lock();
        try {
            if (q.size() == capacity) {
                return false;
            }
            q.offer(item);
            System.out.println(Thread.currentThread().getName() + " OFFER: " + item + " | Size: " + q.size());
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int size() {
        lock.lock();
        try {
            return q.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean isEmpty() {
        lock.lock();
        try {
            return q.isEmpty();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean isFull() {
        lock.lock();
        try {
            return q.size() == capacity;
        } finally {
            lock.unlock();
        }
    }
}

class Bounded_Buffer_Semaphores<T> implements BoundedBuffer<T> {
    private final Semaphore mutex;
    private final Queue<T> q;
    private final Semaphore emptySlots;
    private final Semaphore fullSlots;
    private final int capacity;

    public Bounded_Buffer_Semaphores(int capacity) {
        this.capacity = capacity;
        this.mutex = new Semaphore(1);
        q = new LinkedList<>();
        emptySlots = new Semaphore(capacity);
        fullSlots = new Semaphore(0);
    }

    @Override
    public void put(T item) throws InterruptedException {
        emptySlots.acquire();
        mutex.acquire();
        try {
            q.offer(item);
            System.out.println(Thread.currentThread().getName() + " PUT: " + item + " | Size: " + q.size());
        } finally {
            mutex.release();
            fullSlots.release();
        }
    }

    @Override
    public T take() throws InterruptedException {
        fullSlots.acquire();
        mutex.acquire();
        try {
            T item = q.poll();
            System.out.println(Thread.currentThread().getName() + " TAKE: " + item + " | Size: " + q.size());
            return item;
        } finally {
            mutex.release();
            emptySlots.release();
        }
    }

    @Override
    public boolean offer(T item) {
        if(!emptySlots.tryAcquire()) {
            return false;
        }

        try {
            mutex.acquire();
            try {
                q.offer(item);
                System.out.println(Thread.currentThread().getName() + " OFFER: " + item + " | Size: " + q.size());
                return true;
            } finally {
                mutex.release();
                fullSlots.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emptySlots.release(); // Return the slot we acquired
            return false;
        }
    }

    @Override
    public int size() throws InterruptedException {
        try {
            mutex.acquire();
            try {
                return q.size();
            } finally {
                mutex.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
    }

    @Override
    public boolean isEmpty() {
        return emptySlots.availablePermits() == 0;
    }

    @Override
    public boolean isFull() {
        return fullSlots.availablePermits() == capacity;
    }
}


class Producer implements Runnable {
    private final BoundedBuffer<Integer> buffer;
    private final int itemsToProduce;

    public Producer(BoundedBuffer<Integer> buffer, int itemsToProduce) {
        this.buffer = buffer;
        this.itemsToProduce = itemsToProduce;
    }

    @Override
    public void run() {
        for(int i = 0; i < itemsToProduce; i++) {
            try {
                buffer.put(i);
                Thread.sleep((int)(Math.random() * 100));

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}

class Consumer implements Runnable {
    private final BoundedBuffer<Integer> buffer;
    private final int itemsToConsume;

    public Consumer(BoundedBuffer<Integer> buffer, int itemsToConsume) {
        this.buffer = buffer;
        this.itemsToConsume = itemsToConsume;
    }

    @Override
    public void run() {
        for(int i = 0; i < itemsToConsume; i++) {
            try {
                buffer.take();
                Thread.sleep((int)(Math.random() * 150));

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}



public class Main {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Producer-Consumer with Bounded Buffer ===\n");

        // Buffer capacity
        final int CAPACITY = 5;
        final int ITEMS = 10;

        // Choose implementation to test (change this to test different implementations)
        BoundedBuffer<Integer> buffer = new Bounded_Buffer_Semaphores<>(CAPACITY);

        System.out.println("Testing with capacity: " + CAPACITY);
        System.out.println("Initial state - Empty: " + buffer.isEmpty() + ", Full: " + buffer.isFull() + "\n");

        // Create producers and consumers
        Thread producer1 = new Thread(new Producer(buffer, ITEMS), "Producer-1");
        Thread producer2 = new Thread(new Producer(buffer, ITEMS), "Producer-2");
        Thread consumer1 = new Thread(new Consumer(buffer, ITEMS), "Consumer-1");
        Thread consumer2 = new Thread(new Consumer(buffer, ITEMS), "Consumer-2");

        // Start all threads
        producer1.start();
        producer2.start();
        consumer1.start();
        consumer2.start();

        // Wait for completion
        producer1.join();
        producer2.join();
        consumer1.join();
        consumer2.join();

        System.out.println("\n=== All operations completed ===");
        System.out.println("Final state - Empty: " + buffer.isEmpty() + ", Size: " + buffer.size());
    }
}