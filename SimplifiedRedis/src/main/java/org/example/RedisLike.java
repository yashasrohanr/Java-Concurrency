package org.example;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.*;

/**
 * Single-file Redis-like MVP for LLD interview (Java).
 *
 * Features:
 *  - SET, GET
 *  - EXPIRE with automatic eviction
 *  - Configurable LRU eviction
 *  - AOF append-only log (async writer)
 *  - Thread-safe (ConcurrentHashMap + locks)
 *  - Pub/Sub (in-process)
 *  - Transactions: MULTI/EXEC (simple global lock implementation)
 *
 * Note: This is a compact prototype for interviews — not production-grade.
 */
public class RedisLike {

    public static void main(String[] args) throws Exception {
        KVStore kv = new KVStore(5, Paths.get("aof.log")); // capacity 5
        // Basic usage demo
        kv.set("a", "1");
        kv.set("b", "2");
        System.out.println("GET a: " + kv.get("a"));
        kv.expire("a", 2); // expire in 2 seconds
        System.out.println("TTL demo: waiting 3s ...");
        Thread.sleep(3000);
        System.out.println("GET a after expiry: " + kv.get("a"));

        // LRU eviction demo
        kv.set("k1", "v1");
        kv.set("k2", "v2");
        kv.set("k3", "v3");
        kv.set("k4", "v4"); // now store size will exceed capacity => evict oldest
        kv.get("k2"); // access to update LRU order
        kv.set("k5", "v5");
        kv.set("k6", "v6"); // cause eviction
        System.out.println("Contains k1? " + (kv.get("k1") != null));
        System.out.println("Contains k2? " + (kv.get("k2") != null));

        // Pub/Sub demo
        BlockingQueue<String> q = kv.subscribe("news");
        kv.publish("news", "hello subscribers!");
        System.out.println("Pub/Sub received: " + q.poll(1, TimeUnit.SECONDS));

        // Transaction demo
        kv.multi();
        kv.set("tx1", "100"); // queued
        kv.set("tx2", "200"); // queued
        kv.exec(); // apply atomically
        System.out.println("tx1: " + kv.get("tx1") + ", tx2: " + kv.get("tx2"));

        // Concurrent stress demo (short)
        ExecutorService es = Executors.newFixedThreadPool(4);
        for (int i = 0; i < 20; i++) {
            final int idx = i;
            es.submit(() -> kv.set("con" + (idx % 10), "val" + idx));
        }
        es.shutdown();
        es.awaitTermination(3, TimeUnit.SECONDS);

        kv.shutdown(); // clean stop background threads & flush AOF
        System.out.println("Done. AOF file: aof.log");
    }

    /***********************
     * KVStore & components
     ***********************/
    public static class KVStore {
        private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();
        private final AtomicLong versionCounter = new AtomicLong(0);
        private final TTLManager ttlManager;
        private final LRUCache lru;
        private final AOFWriter aof;
        private final PubSub pubsub = new PubSub();
        private final ReentrantLock txGlobalLock = new ReentrantLock(); // simple global tx lock
        private final ThreadLocal<TransactionContext> txContext = ThreadLocal.withInitial(TransactionContext::new);

        public KVStore(int capacity, Path aofPath) throws IOException {
            this.lru = new LRUCache(capacity, this::evictKeyInternal);
            this.aof = new AOFWriter(aofPath);
            this.ttlManager = new TTLManager(this);
        }

        // ---------- Commands ----------
        public void set(String key, String value) {
            if (inTransaction()) {
                txContext.get().enqueue(() -> setInternal(key, value));
            } else {
                setInternal(key, value);
            }
        }

        private void setInternal(String key, String value) {
            long v = versionCounter.incrementAndGet();
            Entry e = new Entry(value, v, 0L);
            store.put(key, e);
            lru.onPut(key);
            aof.append(String.format("SET %s %s", escape(key), escape(value)));
        }

        public String get(String key) {
            Entry e = store.get(key);
            if (e == null) return null;
            if (e.expireAt != 0 && e.expireAt <= System.currentTimeMillis()) {
                // lazy remove expired
                store.remove(key, e);
                lru.remove(key);
                return null;
            }
            lru.onGet(key);
            return e.value;
        }

        public boolean expire(String key, long seconds) {
            if (inTransaction()) {
                txContext.get().enqueue(() -> expireInternal(key, seconds));
                return true;
            } else {
                return expireInternal(key, seconds);
            }
        }

        private boolean expireInternal(String key, long seconds) {
            Entry old = store.get(key);
            if (old == null) return false;
            long expiry = System.currentTimeMillis() + seconds * 1000;
            long v = versionCounter.incrementAndGet();
            Entry ne = new Entry(old.value, v, expiry);
            store.put(key, ne);
            ttlManager.scheduleExpire(key, expiry, v);
            aof.append(String.format("EXPIRE %s %d", escape(key), seconds));
            return true;
        }

        // Remove called by TTL manager when delay elapsed (only if versions match)
        void removeIfVersionMatches(String key, long version) {
            store.computeIfPresent(key, (k, e) -> {
                if (e.version == version && e.expireAt != 0 && e.expireAt <= System.currentTimeMillis()) {
                    lru.remove(k);
                    return null; // remove
                }
                return e;
            });
        }

        // Eviction invoked by LRUCache when capacity is exceeded
        private void evictKeyInternal(String key) {
            store.remove(key);
            aof.append(String.format("DEL %s", escape(key)));
        }

        // Pub/Sub
        public int publish(String channel, String message) {
            aof.append(String.format("PUBLISH %s %s", escape(channel), escape(message)));
            return pubsub.publish(channel, message);
        }

        public BlockingQueue<String> subscribe(String channel) {
            return pubsub.subscribe(channel);
        }

        // Transactions (simple)
        public void multi() {
            txContext.get().begin();
        }

        public void exec() {
            TransactionContext ctx = txContext.get();
            if (!ctx.inTx) return; // nothing queued
            txGlobalLock.lock();
            try {
                for (Runnable cmd : ctx.commands) cmd.run();
                // group AOF: for simplicity, commands already append to AOF individually in this prototype.
                ctx.reset();
            } finally {
                txGlobalLock.unlock();
            }
        }

        public void discard() {
            txContext.get().reset();
        }

        private boolean inTransaction() {
            return txContext.get().inTx;
        }

        // Subtle helpers
        private static String escape(String s) {
            return s.replace("\n", "\\n");
        }

        // cleanup
        public void shutdown() throws IOException {
            ttlManager.shutdown();
            aof.shutdown();
            pubsub.shutdown();
        }
    }

    /***********************
     * Entry
     ***********************/
    static class Entry {
        final String value;
        final long version;
        volatile long expireAt; // epoch millis, 0 means no expiry

        Entry(String value, long version, long expireAt) {
            this.value = value;
            this.version = version;
            this.expireAt = expireAt;
        }
    }

    /***********************
     * TTLManager (DelayQueue)
     ***********************/
    static class ExpiringKey implements Delayed {
        final String key;
        final long expireAt;
        final long version;
        ExpiringKey(String key, long expireAt, long version) {
            this.key = key; this.expireAt = expireAt; this.version = version;
        }
        public long getDelay(TimeUnit unit) {
            long diff = expireAt - System.currentTimeMillis();
            return unit.convert(diff, TimeUnit.MILLISECONDS);
        }
        public int compareTo(Delayed o) {
            if (o == this) return 0;
            if (o instanceof ExpiringKey) {
                return Long.compare(this.expireAt, ((ExpiringKey)o).expireAt);
            }
            return 0;
        }
    }

    static class TTLManager {
        private final DelayQueue<ExpiringKey> q = new DelayQueue<>();
        private final KVStore store;
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private volatile boolean running = true;

        TTLManager(KVStore store) {
            this.store = store;
            worker.submit(() -> {
                try {
                    while (running && !Thread.currentThread().isInterrupted()) {
                        ExpiringKey ek = q.take();
                        store.removeIfVersionMatches(ek.key, ek.version);
                    }
                } catch (InterruptedException ignored) {}
            });
        }

        void scheduleExpire(String key, long expireAt, long version) {
            q.put(new ExpiringKey(key, expireAt, version));
        }

        void shutdown() {
            running = false;
            worker.shutdownNow();
        }
    }

    /***********************
     * Simple LRU (LinkedHashMap + lock)
     * - On capacity overflow, callback to evict key from store.
     ***********************/
    static class LRUCache {
        private final int capacity;
        private final LinkedHashMap<String, Boolean> map;
        private final ReentrantLock lock = new ReentrantLock();
        private final Consumer<String> evictCallback;

        LRUCache(int capacity, Consumer<String> evictCallback) {
            this.capacity = Math.max(1, capacity);
            this.evictCallback = evictCallback;
            this.map = new LinkedHashMap<>(16, 0.75f, true);
        }

        void onGet(String key) {
            lock.lock();
            try {
                if (map.containsKey(key)) map.get(key); // access order update
            } finally { lock.unlock(); }
        }

        void onPut(String key) {
            String toEvict = null;
            lock.lock();
            try {
                map.put(key, Boolean.TRUE);
                if (map.size() > capacity) {
                    Iterator<String> it = map.keySet().iterator();
                    if (it.hasNext()) {
                        toEvict = it.next();
                        it.remove();
                    }
                }
            } finally { lock.unlock(); }
            if (toEvict != null) evictCallback.accept(toEvict);
        }

        void remove(String key) {
            lock.lock();
            try { map.remove(key); } finally { lock.unlock(); }
        }
    }

    /***********************
     * AOFWriter: async append-only writer
     ***********************/
    static class AOFWriter {
        private final BlockingQueue<String> q = new LinkedBlockingQueue<>();
        private final Thread writerThread;
        private final BufferedWriter bw;
        private volatile boolean running = true;

        AOFWriter(Path path) throws IOException {
            OutputStream os = Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            this.bw = new BufferedWriter(new OutputStreamWriter(os));
            this.writerThread = new Thread(() -> {
                try {
                    while (running) {
                        String cmd = q.poll(500, TimeUnit.MILLISECONDS);
                        if (cmd != null) {
                            bw.write(cmd);
                            bw.newLine();
                        } else {
                            // idle - flush periodically
                        }
                        bw.flush();
                    }
                    // flush remaining
                    String cmd;
                    while ((cmd = q.poll()) != null) {
                        bw.write(cmd); bw.newLine();
                    }
                    bw.flush();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    try { bw.close(); } catch (IOException ignored) {}
                }
            }, "AOF-Writer");
            writerThread.setDaemon(true);
            writerThread.start();
        }

        void append(String cmd) {
            // best-effort: don't block main threads if queue full
            if (!q.offer(cmd)) q.offer(cmd); // try again (rare)
        }

        void shutdown() throws IOException {
            running = false;
            writerThread.interrupt();
            try { writerThread.join(2000); } catch (InterruptedException ignored) {}
            // bw is closed by writer thread
        }
    }

    /***********************
     * Pub/Sub (in-process)
     ***********************/
    static class PubSub {
        private final ConcurrentHashMap<String, CopyOnWriteArrayList<BlockingQueue<String>>> topics = new ConcurrentHashMap<>();
        private final ExecutorService deliverPool = Executors.newCachedThreadPool();

        public int publish(String channel, String message) {
            List<BlockingQueue<String>> subs = topics.getOrDefault(channel, new CopyOnWriteArrayList<>());
            int count = 0;
            for (BlockingQueue<String> q : subs) {
                count++;
                deliverPool.submit(() -> {
                    try { q.offer(message, 500, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
                });
            }
            return count;
        }

        public BlockingQueue<String> subscribe(String channel) {
            BlockingQueue<String> q = new LinkedBlockingQueue<>(100);
            topics.computeIfAbsent(channel, ch -> new CopyOnWriteArrayList<>()).add(q);
            return q;
        }

        public void shutdown() {
            deliverPool.shutdownNow();
            topics.clear();
        }
    }

    /***********************
     * TransactionContext (per-thread)
     ***********************/
    static class TransactionContext {
        volatile boolean inTx = false;
        final List<Runnable> commands = new ArrayList<>();

        void begin() { inTx = true; commands.clear(); }
        void enqueue(Runnable cmd) { if (inTx) commands.add(cmd); else cmd.run(); }
        void reset() { inTx = false; commands.clear(); }
    }
}
