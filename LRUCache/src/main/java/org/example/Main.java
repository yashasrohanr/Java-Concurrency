package org.example;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Concurrent LRU Cache - Multiple Implementations
 *
 * Problem: Implement thread-safe LRU (Least Recently Used) cache
 * Requirements:
 * - O(1) get() and put() operations
 * - Thread-safe concurrent access
 * - Evict least recently used item when capacity reached
 *
 * Four implementations:
 * 1. Synchronized (Simplest)
 * 2. ReentrantReadWriteLock (Better concurrency for read-heavy workloads)
 * 3. ReentrantLock with fine-grained locking
 * 4. Lock-Free using ConcurrentHashMap (Interview twist!)
 */

// ============ INTERFACE ============
interface LRUCache<K, V> {
    /**
     * Get value for key. Updates access order (moves to most recent).
     * @return value or null if not found
     */
    V get(K key);

    /**
     * Put key-value pair. Evicts LRU item if capacity exceeded.
     * Updates access order (moves to most recent).
     */
    void put(K key, V value);

    /**
     * Get current size
     */
    int size();

    /**
     * Get capacity
     */
    int capacity();

    /**
     * Remove key
     */
    V remove(K key);
}

// ============ IMPLEMENTATION 1: SYNCHRONIZED ============
/**
 * Simplest implementation using synchronized keyword.
 *
 * Pros:
 * - Simple, easy to understand
 * - Correct and deadlock-free
 *
 * Cons:
 * - All operations are serialized (even reads)
 * - Poor scalability under high contention
 *
 * Data Structure:
 * - HashMap for O(1) lookup
 * - Doubly-linked list for O(1) reordering and eviction
 */
class SynchronizedLRUCache<K, V> implements LRUCache<K, V> {

    // Doubly-linked list node
    private static class Node<K, V> {
        K key;
        V value;
        Node<K, V> prev;
        Node<K, V> next;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    private final int capacity;
    private final Map<K, Node<K, V>> cache;

    // Dummy head and tail for doubly-linked list (sentinels)
    private final Node<K, V> head;
    private final Node<K, V> tail;

    public SynchronizedLRUCache(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        this.capacity = capacity;
        this.cache = new HashMap<>();

        // Initialize dummy nodes
        this.head = new Node<>(null, null);
        this.tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
    }

    @Override
    public synchronized V get(K key) {
        Node<K, V> node = cache.get(key);
        if (node == null) {
            return null; // Cache miss
        }

        // Move to most recent (front of list)
        moveToHead(node);
        return node.value;
    }

    @Override
    public synchronized void put(K key, V value) {
        Node<K, V> node = cache.get(key);

        if (node != null) {
            // Key exists: update value and move to front
            node.value = value;
            moveToHead(node);
        } else {
            // New key: create node
            Node<K, V> newNode = new Node<>(key, value);
            cache.put(key, newNode);
            addToHead(newNode);

            // Check capacity and evict if necessary
            if (cache.size() > capacity) {
                // Remove least recently used (tail)
                Node<K, V> lru = removeTail();
                cache.remove(lru.key);
            }
        }
    }

    @Override
    public synchronized V remove(K key) {
        Node<K, V> node = cache.get(key);
        if (node == null) return null;

        removeNode(node);
        cache.remove(key);
        return node.value;
    }

    @Override
    public synchronized int size() {
        return cache.size();
    }

    @Override
    public int capacity() {
        return capacity;
    }

    // ===== Doubly-linked list operations (O(1)) =====

    /**
     * Add node right after head (most recent position)
     */
    private void addToHead(Node<K, V> node) {
        node.next = head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;
    }

    /**
     * Remove node from its current position
     */
    private void removeNode(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    /**
     * Move existing node to head (most recent)
     */
    private void moveToHead(Node<K, V> node) {
        removeNode(node);
        addToHead(node);
    }

    /**
     * Remove and return tail (least recently used)
     */
    private Node<K, V> removeTail() {
        Node<K, V> lru = tail.prev;
        removeNode(lru);
        return lru;
    }
}


// ============ IMPLEMENTATION 1.5: SYNCHRONIZED WITH TTL============
/**
 * Thread-safe LRU Cache with TTL (Time To Live) using synchronized
 * Evicts both least recently used and expired entries
 */
class SynchronizedLRUCacheWithTTL<K, V> implements LRUCache<K, V> {

    // Doubly-linked list node with TTL info
    private static class Node<K, V> {
        K key;
        V value;
        long expiryTime; // epoch millis
        Node<K, V> prev, next;

        Node(K key, V value, long expiryTime) {
            this.key = key;
            this.value = value;
            this.expiryTime = expiryTime;
        }
    }

    private final int capacity;
    private final long ttlMillis;
    private final Map<K, Node<K, V>> cache;
    private final Node<K, V> head, tail;
    private final ScheduledExecutorService service;
    public SynchronizedLRUCacheWithTTL(int capacity, long ttlMillis) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        if (ttlMillis <= 0) throw new IllegalArgumentException("TTL must be positive");

        this.capacity = capacity;
        this.ttlMillis = ttlMillis;
        this.cache = new HashMap<>();

        // Dummy sentinels
        this.head = new Node<>(null, null, 0);
        this.tail = new Node<>(null, null, 0);
        head.next = tail;
        tail.prev = head;
        service = Executors.newSingleThreadScheduledExecutor();
        service.scheduleAtFixedRate(() -> {
            cleanupExpired();
        }, 5000L, 1000L, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized V get(K key) {
        Node<K, V> node = cache.get(key);
        if (node == null) return null;

        // Check TTL
        if (isExpired(node)) {
            removeNode(node);
            cache.remove(key);
            return null;
        }

        moveToHead(node);
        return node.value;
    }

    @Override
    public synchronized void put(K key, V value) {
        Node<K, V> node = cache.get(key);
        long expiry = System.currentTimeMillis() + ttlMillis;

        if (node != null) {
            // Update existing value + expiry
            node.value = value;
            node.expiryTime = expiry;
            moveToHead(node);
        } else {
            // Insert new node
            Node<K, V> newNode = new Node<>(key, value, expiry);
            cache.put(key, newNode);
            addToHead(newNode);

            if (cache.size() > capacity) {
                Node<K, V> lru = removeTail();
                cache.remove(lru.key);
            }
        }
    }

    @Override
    public synchronized V remove(K key) {
        Node<K, V> node = cache.remove(key);
        if (node == null) return null;
        removeNode(node);
        return node.value;
    }

    @Override
    public synchronized int size() {
        cleanupExpired();
        return cache.size();
    }

    @Override
    public int capacity() {
        return capacity;
    }

    // ===== Helper Methods =====

    private boolean isExpired(Node<K, V> node) {
        return System.currentTimeMillis() > node.expiryTime;
    }

    // Clean up expired entries lazily
    private void cleanupExpired() {
        Iterator<Map.Entry<K, Node<K, V>>> it = cache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<K, Node<K, V>> e = it.next();
            if (isExpired(e.getValue())) {
                removeNode(e.getValue());
                it.remove();
            }
        }
    }

    private void addToHead(Node<K, V> node) {
        node.next = head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;
    }

    private void removeNode(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    private void moveToHead(Node<K, V> node) {
        removeNode(node);
        addToHead(node);
    }

    private Node<K, V> removeTail() {
        Node<K, V> lru = tail.prev;
        removeNode(lru);
        return lru;
    }
}

// ============ IMPLEMENTATION 2: READ-WRITE LOCK ============
/**
 * Uses ReentrantReadWriteLock for better read concurrency.
 *
 * Pros:
 * - Multiple concurrent readers (if no writer)
 * - Better for read-heavy workloads
 *
 * Cons:
 * - get() must acquire write lock to update access order
 * - More complex than synchronized
 * - Still serializes all access-order updates
 *
 * Key Insight: Even reads must modify state (access order),
 * so we need write lock for both get() and put()!
 */
class ReadWriteLockLRUCache<K, V> implements LRUCache<K, V> {

    private static class Node<K, V> {
        K key;
        V value;
        Node<K, V> prev;
        Node<K, V> next;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    private final int capacity;
    private final Map<K, Node<K, V>> cache;
    private final Node<K, V> head;
    private final Node<K, V> tail;

    // ReadWriteLock: allows multiple readers OR single writer
    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final Lock readLock = rwLock.readLock();
    private final Lock writeLock = rwLock.writeLock();

    public ReadWriteLockLRUCache(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        this.capacity = capacity;
        this.cache = new HashMap<>();
        this.head = new Node<>(null, null);
        this.tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
    }

    @Override
    public V get(K key) {
        // ⚠️ CRITICAL: get() needs WRITE lock because it modifies access order!
        // This is a common interview trap question
        writeLock.lock();
        try {
            Node<K, V> node = cache.get(key);
            if (node == null) return null;

            moveToHead(node);
            return node.value;
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void put(K key, V value) {
        writeLock.lock();
        try {
            Node<K, V> node = cache.get(key);

            if (node != null) {
                node.value = value;
                moveToHead(node);
            } else {
                Node<K, V> newNode = new Node<>(key, value);
                cache.put(key, newNode);
                addToHead(newNode);

                if (cache.size() > capacity) {
                    Node<K, V> lru = removeTail();
                    cache.remove(lru.key);
                }
            }
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public V remove(K key) {
        writeLock.lock();
        try {
            Node<K, V> node = cache.get(key);
            if (node == null) return null;

            removeNode(node);
            cache.remove(key);
            return node.value;
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public int size() {
        readLock.lock(); // Size check is read-only
        try {
            return cache.size();
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public int capacity() {
        return capacity;
    }

    // Linked list operations (same as synchronized version)
    private void addToHead(Node<K, V> node) {
        node.next = head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;
    }

    private void removeNode(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    private void moveToHead(Node<K, V> node) {
        removeNode(node);
        addToHead(node);
    }

    private Node<K, V> removeTail() {
        Node<K, V> lru = tail.prev;
        removeNode(lru);
        return lru;
    }
}

// ============ IMPLEMENTATION 3: LOCK-FREE (INTERVIEW TWIST!) ============
/**
 * Lock-free implementation using ConcurrentHashMap.
 *
 * Challenge: How to maintain LRU order without locks?
 * Solution: Use access counters and periodic cleanup
 *
 * Trade-offs:
 * + True lock-free reads and writes
 * + Excellent scalability under high contention
 * + No blocking or deadlocks
 * - Approximate LRU (not perfect ordering)
 * - Requires periodic cleanup
 * - More memory overhead
 *
 * This is what's asked in "make it lock-free" interview twist!
 */
class LockFreeLRUCache<K, V> implements LRUCache<K, V> {

    /**
     * Node with access timestamp for LRU tracking
     */
    private static class AccessNode<K, V> {
        final K key;
        volatile V value;
        volatile long accessTime; // Last access timestamp (monotonic)

        AccessNode(K key, V value) {
            this.key = key;
            this.value = value;
            this.accessTime = System.nanoTime();
        }

        void updateAccess() {
            this.accessTime = System.nanoTime();
        }
    }

    private final int capacity;

    // ConcurrentHashMap provides lock-free reads/writes
    private final ConcurrentHashMap<K, AccessNode<K, V>> cache;

    // Track approximate size (may be slightly off due to concurrent operations)
    private final AtomicInteger size;

    public LockFreeLRUCache(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        this.capacity = capacity;
        this.cache = new ConcurrentHashMap<>(capacity);
        this.size = new AtomicInteger(0);
    }

    @Override
    public V get(K key) {
        AccessNode<K, V> node = cache.get(key);
        if (node == null) return null;

        // Update access time (lock-free!)
        node.updateAccess();
        return node.value;
    }

    @Override
    public void put(K key, V value) {
        AccessNode<K, V> existingNode = cache.get(key);

        if (existingNode != null) {
            // Update existing entry
            existingNode.value = value;
            existingNode.updateAccess();
        } else {
            // New entry
            AccessNode<K, V> newNode = new AccessNode<>(key, value);

            // Try to add if not present
            AccessNode<K, V> prev = cache.putIfAbsent(key, newNode);

            if (prev == null) {
                // Successfully added new entry
                int currentSize = size.incrementAndGet();

                // Check if we need to evict (approximate check)
                if (currentSize > capacity) {
                    evictLRU();
                }
            } else {
                // Another thread added it first, update that node
                prev.value = value;
                prev.updateAccess();
            }
        }
    }

    @Override
    public V remove(K key) {
        AccessNode<K, V> node = cache.remove(key);
        if (node != null) {
            size.decrementAndGet();
            return node.value;
        }
        return null;
    }

    @Override
    public int size() {
        return size.get(); // Approximate
    }

    @Override
    public int capacity() {
        return capacity;
    }

    /**
     * Evict least recently used entries (approximate LRU).
     *
     * Strategy: Find entries with oldest access time and remove them.
     * This is O(n) but runs infrequently only when capacity exceeded.
     *
     * ⚠️ NOTE: This is an approximation! Multiple threads might be
     * evicting simultaneously, but that's OK - we'll converge to
     * size ~= capacity eventually.
     */
    private void evictLRU() {
        // Only evict if we're over capacity
        while (size.get() > capacity) {
            // Find oldest entry
            K oldestKey = null;
            long oldestTime = Long.MAX_VALUE;

            for (Map.Entry<K, AccessNode<K, V>> entry : cache.entrySet()) {
                AccessNode<K, V> node = entry.getValue();
                if (node.accessTime < oldestTime) {
                    oldestTime = node.accessTime;
                    oldestKey = entry.getKey();
                }
            }

            if (oldestKey != null) {
                // Try to remove (might fail if already removed by another thread)
                if (cache.remove(oldestKey) != null) {
                    size.decrementAndGet();
                }
            } else {
                // No entry found (race with other threads), break
                break;
            }
        }
    }
}

// ============ IMPLEMENTATION 4: SEGMENTED LOCK (BONUS!) ============
/**
 * Uses multiple locks (segments) for better concurrency.
 * Similar to how ConcurrentHashMap works internally.
 *
 * This is an advanced technique rarely asked but impressive to mention!
 */
class SegmentedLRUCache<K, V> implements LRUCache<K, V> {

    private static class Node<K, V> {
        K key;
        V value;
        Node<K, V> prev;
        Node<K, V> next;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    /**
     * Each segment has its own lock and LRU list
     */
    private static class Segment<K, V> {
        private final Map<K, Node<K, V>> cache = new HashMap<>();
        private final Node<K, V> head = new Node<>(null, null);
        private final Node<K, V> tail = new Node<>(null, null);
        private final ReentrantLock lock = new ReentrantLock();
        private final int capacity;

        Segment(int capacity) {
            this.capacity = capacity;
            head.next = tail;
            tail.prev = head;
        }

        V get(K key) {
            lock.lock();
            try {
                Node<K, V> node = cache.get(key);
                if (node == null) return null;
                moveToHead(node);
                return node.value;
            } finally {
                lock.unlock();
            }
        }

        void put(K key, V value) {
            lock.lock();
            try {
                Node<K, V> node = cache.get(key);
                if (node != null) {
                    node.value = value;
                    moveToHead(node);
                } else {
                    Node<K, V> newNode = new Node<>(key, value);
                    cache.put(key, newNode);
                    addToHead(newNode);

                    if (cache.size() > capacity) {
                        Node<K, V> lru = removeTail();
                        cache.remove(lru.key);
                    }
                }
            } finally {
                lock.unlock();
            }
        }

        V remove(K key) {
            lock.lock();
            try {
                Node<K, V> node = cache.get(key);
                if (node == null) return null;
                removeNode(node);
                cache.remove(key);
                return node.value;
            } finally {
                lock.unlock();
            }
        }

        int size() {
            lock.lock();
            try {
                return cache.size();
            } finally {
                lock.unlock();
            }
        }

        private void addToHead(Node<K, V> node) {
            node.next = head.next;
            node.prev = head;
            head.next.prev = node;
            head.next = node;
        }

        private void removeNode(Node<K, V> node) {
            node.prev.next = node.next;
            node.next.prev = node.prev;
        }

        private void moveToHead(Node<K, V> node) {
            removeNode(node);
            addToHead(node);
        }

        private Node<K, V> removeTail() {
            Node<K, V> lru = tail.prev;
            removeNode(lru);
            return lru;
        }
    }

    private final int capacity;
    private final int segmentCount;
    private final Segment<K, V>[] segments;

    @SuppressWarnings("unchecked")
    public SegmentedLRUCache(int capacity, int segmentCount) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        this.capacity = capacity;
        this.segmentCount = segmentCount;
        this.segments = new Segment[segmentCount];

        int capacityPerSegment = Math.max(1, capacity / segmentCount);
        for (int i = 0; i < segmentCount; i++) {
            segments[i] = new Segment<>(capacityPerSegment);
        }
    }

    public SegmentedLRUCache(int capacity) {
        this(capacity, 16); // Default 16 segments like ConcurrentHashMap
    }

    private Segment<K, V> getSegment(K key) {
        int hash = key.hashCode();
        int index = (hash & 0x7FFFFFFF) % segmentCount;
        return segments[index];
    }

    @Override
    public V get(K key) {
        return getSegment(key).get(key);
    }

    @Override
    public void put(K key, V value) {
        getSegment(key).put(key, value);
    }

    @Override
    public V remove(K key) {
        return getSegment(key).remove(key);
    }

    @Override
    public int size() {
        int total = 0;
        for (Segment<K, V> segment : segments) {
            total += segment.size();
        }
        return total;
    }

    @Override
    public int capacity() {
        return capacity;
    }
}


public class Main {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Concurrent LRU Cache Comparison ===\n");

        int capacity = 1000;
        int operations = 10000;
        int threads = 10;

        // Test all implementations
        testCache("SYNCHRONIZED", new SynchronizedLRUCache<>(capacity), operations, threads);
        testCache("READ-WRITE LOCK", new ReadWriteLockLRUCache<>(capacity), operations, threads);
        testCache("LOCK-FREE", new LockFreeLRUCache<>(capacity), operations, threads);
        testCache("SEGMENTED (16)", new SegmentedLRUCache<>(capacity, 16), operations, threads);
    }

    private static void testCache(String name, LRUCache<Integer, String> cache,
                                  int operations, int numThreads) throws InterruptedException {
        System.out.println("Testing: " + name);

        long startTime = System.nanoTime();
        Thread[] threads = new Thread[numThreads];

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            threads[t] = new Thread(() -> {
                Random rand = new Random(threadId);
                for (int i = 0; i < operations; i++) {
                    int key = rand.nextInt(cache.capacity() * 2);

                    if (rand.nextBoolean()) {
                        // 50% reads
                        cache.get(key);
                    } else {
                        // 50% writes
                        cache.put(key, "value" + key);
                    }
                }
            });
            threads[t].start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        long duration = System.nanoTime() - startTime;
        double opsPerSecond = (operations * numThreads * 1_000_000_000.0) / duration;

        System.out.printf("  Time: %.2f ms | Throughput: %.0f ops/sec | Final size: %d%n%n",
                duration / 1_000_000.0, opsPerSecond, cache.size());
    }
}

/**
 * ==================== INTERVIEW TALKING POINTS ====================
 *
 * 1. DATA STRUCTURE CHOICE:
 *    Q: "Why HashMap + Doubly-Linked List?"
 *    A: "HashMap gives O(1) lookup. Doubly-linked list gives O(1) reorder
 *        and eviction. We need both for efficient LRU."
 *
 * 2. SYNCHRONIZED VS READ-WRITE LOCK:
 *    Q: "Which is better?"
 *    A: "Depends on workload. For LRU, even reads modify state (access order),
 *        so ReadWriteLock doesn't help much. Synchronized is simpler and often
 *        faster due to JVM optimizations (biased locking)."
 *
 * 3. LOCK-FREE CHALLENGE:
 *    Q: "How to make it lock-free?"
 *    A: "Use ConcurrentHashMap for storage. Track access time instead of
 *        linked list. Accept approximate LRU (not perfect ordering).
 *        Trade-off: Better concurrency for less precise eviction."
 *
 * 4. EVICTION RACE CONDITIONS:
 *    Q: "What if two threads try to evict simultaneously?"
 *    A: "With locks: Serialized, only one evicts.
 *        Without locks: Both might scan, but ConcurrentHashMap ensures
 *        atomic removal. Worst case: Evict one extra item."
 *
 * 5. SEGMENTED APPROACH (BONUS):
 *    Q: "How to improve scalability further?"
 *    A: "Segment the cache (like ConcurrentHashMap v7). Each segment has
 *        its own lock. Different keys can be accessed concurrently if in
 *        different segments. Trade-off: More complex, less global LRU accuracy."
 *
 * 6. COMMON PITFALLS:
 *    ❌ Forgetting to update access order on get()
 *    ❌ Using ReadWriteLock incorrectly (read lock for get)
 *    ❌ Not handling eviction atomically
 *    ❌ Memory leaks from not updating references
 *    ❌ Deadlock from acquiring multiple locks
 *
 * 7. PRODUCTION CONSIDERATIONS:
 *    - Use LinkedHashMap with accessOrder=true for simple cases
 *    - Use Guava Cache or Caffeine for production
 *    - Consider TTL (time-to-live) in addition to LRU
 *    - Add metrics (hit rate, eviction rate)
 *    - Consider write-through vs write-back caching
 *
 * 8. COMPLEXITY ANALYSIS:
 *    get():  O(1) time, O(1) amortized (with doubly-linked list)
 *    put():  O(1) time, O(1) amortized
 *    Space:  O(capacity) - stores up to capacity entries
 *
 *    Lock-free version:
 *    get():  O(1) expected (ConcurrentHashMap)
 *    put():  O(1) expected, O(n) worst case during eviction
 *    Space:  O(capacity + overhead for timestamps)
 */