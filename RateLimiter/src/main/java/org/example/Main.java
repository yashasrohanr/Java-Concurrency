package org.example;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

interface RateLimiter {
    /**
     * Try to acquire permission for a request
     * @param userId User identifier (null for global limiting)
     * @return true if request is allowed, false if rate limit exceeded
     */
    boolean allowRequest(String userId);

    /**
     * Get current rate limit status
     */
    RateLimitStatus getStatus(String userId);
}

class RateLimitStatus {
    private final int allowed;
    private final int remaining;
    private final long resetTime;

    public RateLimitStatus(int allowed, int remaining, long resetTime) {
        this.allowed = allowed;
        this.remaining = remaining;
        this.resetTime = resetTime;
    }

    @Override
    public String toString() {
        return "RateLimitStatus{" +
                "allowed=" + allowed +
                ", remaining=" + remaining +
                ", resetTime=" + (resetTime - System.currentTimeMillis()) +
                '}';
    }
}

class TokenBucket {
    private final Semaphore tokens;
    private final int capacity;

    public TokenBucket(int capacity) {
        this.tokens = new Semaphore(capacity);
        this.capacity = capacity;
    }

    public boolean tryAcquire() {
        return tokens.tryAcquire();
    }

    void refill(int count) {
        int toRelease = Math.min(count, capacity - tokens.availablePermits());
        tokens.release(toRelease);
    }

    int availableTokens() {
        return tokens.availablePermits();
    }
}

// ============ STRATEGY 1: TOKEN BUCKET (Semaphore-based) ============
/**
 * Token Bucket: Tokens are added at a constant rate. Each request consumes a token.
 * Allows bursts up to bucket capacity.
 *
 * Best for: Systems that can handle bursts but need average rate control
 */
class TokenBucketRateLimiter implements RateLimiter {
    private final int capacity;
    private final int refillRate;
    private final ConcurrentHashMap<String, TokenBucket> userBuckets;
    private final ScheduledExecutorService scheduler;
    private final TokenBucket globalBucket;

    public TokenBucketRateLimiter(int capacity, int refillRate) {
        this.capacity = capacity;
        this.refillRate = refillRate;
        this.scheduler = Executors.newScheduledThreadPool(1);
        this.userBuckets = new ConcurrentHashMap<>();
        this.globalBucket = new TokenBucket(capacity);

        long refillInterval = 1000L / refillRate;

        scheduler.scheduleAtFixedRate(this::refillTokens, refillInterval, refillInterval, TimeUnit.MILLISECONDS);
    }

    private void refillTokens() {
        globalBucket.refill(1);
        for(TokenBucket bucket : userBuckets.values()) {
            bucket.refill(1);
        }
    }

    @Override
    public boolean allowRequest(String userId) {
        if(userId == null) {
            return globalBucket.tryAcquire();
        }
        TokenBucket bucket = userBuckets.computeIfAbsent(userId, k-> new TokenBucket(this.capacity));
        return bucket.tryAcquire();
    }

    @Override
    public RateLimitStatus getStatus(String userId) {
        TokenBucket bucket = userId == null ? globalBucket : userBuckets.getOrDefault(userId, globalBucket);

        return new RateLimitStatus(capacity, bucket.availableTokens(),
                System.currentTimeMillis() + 1000);
    }

    public void shutDown() {
        scheduler.shutdown();
        userBuckets.clear();
    }
}

// ============ STRATEGY 2: LEAKY BUCKET ============
/**
 * Leaky Bucket: Requests are processed at a constant rate like water leaking from a bucket.
 * Requests queue up and are processed steadily.
 *
 * Best for: Smooth, constant output rate regardless of input bursts
 */
class LeakyBucketRateLimiter implements RateLimiter{
    private final int capacity;
    private final long leakIntervalMs;
    private final ConcurrentHashMap<String, LockFreeLeakyBucket> userBuckets;
    private final LockFreeLeakyBucket globalBucket;

    LeakyBucketRateLimiter(int capacity, long leakIntervalMs) {
        this.capacity = capacity;
        this.leakIntervalMs = leakIntervalMs;
        this.globalBucket = new LockFreeLeakyBucket(capacity, leakIntervalMs);
        this.userBuckets = new ConcurrentHashMap<>();
    }

    @Override
    public boolean allowRequest(String userId) {
        if(userId == null) {
            return globalBucket.allowRequests();
        }
        LockFreeLeakyBucket bucket = userBuckets.computeIfAbsent(userId, k-> new LockFreeLeakyBucket(this.capacity, this.leakIntervalMs));

        return bucket.allowRequests();
    }

    @Override
    public RateLimitStatus getStatus(String userId) {
        LockFreeLeakyBucket bucket = (userId == null)
                ? globalBucket
                : userBuckets.getOrDefault(userId, globalBucket);

        int remaining = bucket.getAvailableCapacity();
        long resetTime = System.currentTimeMillis() + leakIntervalMs;
        return new RateLimitStatus(capacity, remaining, resetTime);
    }

    private static class LockFreeLeakyBucket {
        private final int capacity;
        private final long leakIntervalMs;
        private final AtomicInteger currentSize = new AtomicInteger(0);
        private final AtomicLong lastLeakTime = new AtomicLong(System.currentTimeMillis());

        LockFreeLeakyBucket(int capacity, long leakIntervalMs) {
            this.capacity = capacity;
            this.leakIntervalMs = leakIntervalMs;
        }

        boolean allowRequests() {
            while(true) {
                int size = currentSize.get();
                long lastLeak = lastLeakTime.get();

                long now = System.currentTimeMillis();
                long timePassed = now - lastLeak;
                int leakedRequests = (int)(timePassed / leakIntervalMs);
                int newSize = Math.max(0, size - leakedRequests);

                if(newSize >= capacity) return false;

                long newLastLeak = lastLeak + leakedRequests * leakIntervalMs;
                int finalSize = newSize + 1;

                // update size first, then time
                if (currentSize.compareAndSet(size, finalSize)) {
                    lastLeakTime.compareAndSet(lastLeak, newLastLeak);
                    return true;
                }
                Thread.onSpinWait();
            }
        }
        int getAvailableCapacity() {
            long now = System.currentTimeMillis();
            long lastLeak = lastLeakTime.get();
            long timePassed = now - lastLeak;
            int leakedRequests = (int) (timePassed / leakIntervalMs);
            int size = currentSize.get();
            int newSize = Math.max(0, size - leakedRequests);
            return capacity - newSize;
        }
    }

}


class FixedWindowLimiter implements RateLimiter{
    private final int maxRequestsPerWindow;
    private final long windowSizeMs;
    private final ConcurrentHashMap<String, FixedWindowLimiter.FixedWindowBucket> userBuckets;
    private final FixedWindowLimiter.FixedWindowBucket globalBucket;

    private class FixedWindowBucket {
        private final AtomicInteger requests;
        private final AtomicLong windowStart;

        FixedWindowBucket() {
            this.windowStart = new AtomicLong(System.currentTimeMillis());
            this.requests = new AtomicInteger(0);
        }

        boolean allowRequests() {
            long now = System.currentTimeMillis();
            long timeElapsed = now - windowStart.get();

            if (timeElapsed >= windowSizeMs) {
                if(windowStart.compareAndSet(windowStart.get(), now)) {
                    requests.set(0);
                }
            }

            int count = requests.incrementAndGet();
            if(count > maxRequestsPerWindow) {
                requests.decrementAndGet();
                return false;
            }
            return true;
        }
        RateLimitStatus getStatus() {
            long now = System.currentTimeMillis();
            long currentWindowStart = windowStart.get();
            long resetTime = currentWindowStart + windowSizeMs;
            int remaining = Math.max(0, maxRequestsPerWindow - requests.get());
            return new RateLimitStatus(maxRequestsPerWindow, remaining, resetTime);
        }
    }

    public FixedWindowLimiter(int capacity, long leakIntervalMs) {
        this.maxRequestsPerWindow = capacity;
        this.windowSizeMs = leakIntervalMs;
        userBuckets = new ConcurrentHashMap<>();
        globalBucket = new FixedWindowBucket();
    }

    @Override
    public boolean allowRequest(String userId) {
        if(userId == null) {
            return globalBucket.allowRequests();
        }
        return userBuckets
                .computeIfAbsent(userId, k-> new FixedWindowBucket())
                .allowRequests();
    }

    @Override
    public RateLimitStatus getStatus(String userId) {
        if(userId == null) {
            return globalBucket.getStatus();
        }
        return userBuckets
                .computeIfAbsent(userId, k-> new FixedWindowBucket())
                .getStatus();
    }
}

// ============ STRATEGY 4: SLIDING WINDOW LOG ============
/**
 * Sliding Window Log: Keeps log of all request timestamps.
 * Most accurate but memory intensive.
 *
 * Best for: Precise rate limiting where accuracy is critical
 */
class SlidingWindowLog implements RateLimiter{
    private final int maxRequests;
    private final long windowSizeMs;
    private final ConcurrentHashMap<String, RequestLog> userLogs;
    private final RequestLog globalLog;
    private final ScheduledExecutorService cleaner;
    
    private class RequestLog{
        private final ConcurrentLinkedDeque<Long> timestamps;

        private RequestLog() {
            this.timestamps = new ConcurrentLinkedDeque<>();
        }

        public void cleanup() {
            long now = System.currentTimeMillis();
            long cutoff = now - windowSizeMs;
            while(!timestamps.isEmpty() && timestamps.peek() < cutoff) {
                timestamps.poll();
            }
        }

        public boolean allowRequest() {
            synchronized (this) {
                cleanup();
                if (timestamps.size() >= maxRequests) {
                    return false;
                }
                timestamps.offer(System.currentTimeMillis());
                return true;
            }
        }

        RateLimitStatus getStatus() {
            cleanup();
            int remaining = Math.max(0, maxRequests - timestamps.size());
            return new RateLimitStatus(maxRequests, remaining,
                    System.currentTimeMillis() + windowSizeMs);
        }
    }
    
    public SlidingWindowLog(int maxRequests, long windowSizeMs) {
        this.maxRequests = maxRequests;
        this.windowSizeMs = windowSizeMs;
        this.userLogs = new ConcurrentHashMap<>();
        this.globalLog = new RequestLog();
        this.cleaner = Executors.newScheduledThreadPool(1);
        
        cleaner.scheduleAtFixedRate(this::cleanOldEntries, windowSizeMs, windowSizeMs, TimeUnit.MILLISECONDS);
    }

    private void cleanOldEntries() {
        globalLog.cleanup();
        userLogs.values().forEach(RequestLog::cleanup);
    }


    @Override
    public boolean allowRequest(String userId) {
        if (userId == null) {
            return globalLog.allowRequest();
        }

        RequestLog log = userLogs.computeIfAbsent(userId, k -> new RequestLog());
        return log.allowRequest();
    }

    @Override
    public RateLimitStatus getStatus(String userId) {
        RequestLog log = userId == null ? globalLog :
                userLogs.getOrDefault(userId, globalLog);
        return log.getStatus();
    }

    public void shutDown() {
        this.cleaner.shutdown();
    }
}

// ============ STRATEGY 5: SLIDING WINDOW COUNTER ============
/**
 * Sliding Window Counter: Hybrid of fixed window and sliding window.
 * Uses weighted count from previous and current window.
 *
 * Best for: Balance between accuracy and memory efficiency
 */
class SlidingWindowCounterRateLimiter implements RateLimiter {
    private final int maxRequests;
    private final long windowSizeMs;
    private final ConcurrentHashMap<String, SlidingCounter> userCounters;
    private final SlidingCounter globalCounter;

    public SlidingWindowCounterRateLimiter(int maxRequests, long windowSizeMs) {
        this.maxRequests = maxRequests;
        this.windowSizeMs = windowSizeMs;
        this.userCounters = new ConcurrentHashMap<>();
        this.globalCounter = new SlidingCounter();
    }

    @Override
    public boolean allowRequest(String userId) {
        if (userId == null) {
            return globalCounter.allowRequest();
        }

        SlidingCounter counter = userCounters.computeIfAbsent(userId,
                k -> new SlidingCounter());
        return counter.allowRequest();
    }

    @Override
    public RateLimitStatus getStatus(String userId) {
        SlidingCounter counter = userId == null ? globalCounter :
                userCounters.getOrDefault(userId, globalCounter);
        return counter.getStatus();
    }

    private class SlidingCounter {
        private final AtomicLong currentWindowStart;
        private final AtomicInteger currentWindowCount;
        private final AtomicInteger previousWindowCount;

        SlidingCounter() {
            this.currentWindowStart = new AtomicLong(System.currentTimeMillis());
            this.currentWindowCount = new AtomicInteger(0);
            this.previousWindowCount = new AtomicInteger(0);
        }

        boolean allowRequest() {
            long now = System.currentTimeMillis();
            updateWindows(now);

            double estimatedCount = getEstimatedCount(now);

            if(estimatedCount >= maxRequests) {
                return false;
            }
            currentWindowCount.incrementAndGet();
            return true;
        }

        private void updateWindows(long now) {
            long windowStart = currentWindowStart.get();

            if (now - windowStart >= windowSizeMs) {
                synchronized (this) {
                    windowStart = currentWindowStart.get();
                    if (now - windowStart >= windowSizeMs) {
                        previousWindowCount.set(currentWindowCount.get());
                        currentWindowCount.set(0);
                        currentWindowStart.set(now);
                    }
                }
            }
        }

        private double getEstimatedCount(long now) {
            long windowStart = currentWindowStart.get();
            double timeIntoWindow = now - windowStart;
            double percentageOfWindow = timeIntoWindow / windowSizeMs;

            int prevCount = previousWindowCount.get();
            int currCount = currentWindowCount.get();


            return (prevCount * (1 - percentageOfWindow)) + currCount;

        }

        RateLimitStatus getStatus() {
            long now = System.currentTimeMillis();
            updateWindows(now);
            double estimatedCount = getEstimatedCount(now);
            int remaining = (int)Math.max(0, maxRequests - estimatedCount);
            return new RateLimitStatus(maxRequests, remaining,
                    currentWindowStart.get() + windowSizeMs);
        }
    }
}

class RohanRateLimiter{
    // token bucket
    private static class Bucket {
        static int capacity;
        // per second refill rate
        static int windowSize;
        static long lastRefillTime;
        private static Semaphore permits;
        public Bucket(int capacity, int windowSize) {
            this.windowSize = windowSize;
            this.capacity = capacity;
            this.permits = new Semaphore(capacity);
            lastRefillTime = System.currentTimeMillis();
        }

        public boolean allowRequest() {
            return permits.tryAcquire();
        }

        public void refill() {
            long now = System.currentTimeMillis();
            long timeElapsed = now - lastRefillTime;
            int  tokensToFill = Math.min(capacity, (int) ((timeElapsed * windowSize) / 1000));

            if (tokensToFill > 0) {
                permits.release(tokensToFill);
                lastRefillTime = now;
            }
        }

    }
    private final int capacity;
    private final int windowSize;
    private final Bucket globalBucket;
    private final ConcurrentHashMap<String, Bucket> userBuckets;
    private final ScheduledExecutorService scheduledService;

    public RohanRateLimiter(int windowSize, int capacity) {
        this.scheduledService = Executors.newSingleThreadScheduledExecutor();
        this.globalBucket = new Bucket(capacity, windowSize);
        this.windowSize = windowSize;
        this.capacity = capacity;
        this.userBuckets = new ConcurrentHashMap<>();

        scheduledService.scheduleAtFixedRate(() -> {
            globalBucket.refill();
            userBuckets.values().forEach(Bucket::refill);
        }, windowSize, windowSize, TimeUnit.MILLISECONDS);
    }

    public boolean allowRequest(String userId) {
        Bucket bucket = (userId == null) ? globalBucket : userBuckets.computeIfAbsent(userId, user -> new Bucket(capacity, windowSize));

        return bucket.allowRequest();
    }

    public void shutDown() {
        scheduledService.shutdown();
        userBuckets.clear();
    }
}




public class Main {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Rate Limiter Demonstration ===\n");

        // Test all strategies
        testStrategy("TOKEN BUCKET", new TokenBucketRateLimiter(5, 2));
        // every 500 ms leak
        testStrategy("LEAKY BUCKET", new LeakyBucketRateLimiter(5, 2));
        testStrategy("FIXED WINDOW", new FixedWindowLimiter(5, 1000));
        testStrategy("SLIDING WINDOW LOG", new SlidingWindowLog(5, 1000));
        testStrategy("SLIDING WINDOW COUNTER", new SlidingWindowCounterRateLimiter(5, 1000));
    }

    private static void testStrategy(String name, RateLimiter limiter) throws InterruptedException {
        System.out.println("=== Testing: " + name + " ===");

        // Test global rate limiting
        System.out.println("\n[Global Rate Limiting]");
        for (int i = 0; i < 8; i++) {
            boolean allowed = limiter.allowRequest(null);
            System.out.printf("Request %d: %s | %s%n", i + 1,
                    allowed ? "✓ ALLOWED" : "✗ REJECTED",
                    limiter.getStatus(null));
            Thread.sleep(100);
        }

        // Test per-user rate limiting
        System.out.println("\n[Per-User Rate Limiting]");
        String[] users = {"user1", "user2"};
        for (int i = 0; i < 4; i++) {
            for (String user : users) {
                boolean allowed = limiter.allowRequest(user);
                System.out.printf("%s Request %d: %s%n", user, i + 1,
                        allowed ? "✓" : "✗");
            }
        }

        System.out.println("\n" + "=".repeat(50) + "\n");
        Thread.sleep(500);
    }
}