package org.example;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ==================================================================================
 * SDE 3 INTERVIEW - RATE LIMITER LLD (COMPLETE SUITE)
 * ==================================================================================
 * * CORE FEATURES:
 * 1. Supports 4 Algorithms: Token Bucket, Leaky Bucket, Sliding Window Log, Sliding Window Counter.
 * 2. Thread-safe implementation using lazy evaluation and granular locking.
 * 3. Returns detailed headers (Limit, Remaining, Reset Time).
 * 4. Extensible storage design (In-Memory provided, swapable for Redis).
 */

public class RateLimiterSystem {

    // ====================================================
    // 1. DATA TRANSFER OBJECTS (DTOs) & ENUMS
    // ====================================================

    public enum AlgorithmType {
        TOKEN_BUCKET,
        LEAKY_BUCKET,
        SLIDING_WINDOW_LOG,
        SEMAPHORE_TOKEN_BUCKET, LOCK_FREE_TOKEN_BUCKET, SLIDING_WINDOW_COUNTER
    }

    /**
     * Represents the configuration for a specific client.
     */
    public static class RateLimitRule {
        private final int capacity;        // Max requests (Bucket Size / Limit)
        private final int requestsPerUnit; // Rate count (often same as capacity for simple rules)
        private final int timeWindowSeconds; // Rate time unit
        private final AlgorithmType algorithm;

        public RateLimitRule(int capacity, int requestsPerUnit, int timeWindowSeconds, AlgorithmType algorithm) {
            this.capacity = capacity;
            this.requestsPerUnit = requestsPerUnit;
            this.timeWindowSeconds = timeWindowSeconds;
            this.algorithm = algorithm;
        }

        public double getRatePerSecond() {
            return (double) requestsPerUnit / timeWindowSeconds;
        }

        public int getCapacity() { return capacity; }
        public int getTimeWindowSeconds() { return timeWindowSeconds; }
    }

    /**
     * Response object containing the decision and headers.
     */
    public static class RateLimitResponse {
        private final boolean allowed;
        private final int limitHeader;
        private final long remainingHeader;
        private final long resetHeader; // Unix Timestamp

        public RateLimitResponse(boolean allowed, int limitHeader, long remainingHeader, long resetHeader) {
            this.allowed = allowed;
            this.limitHeader = limitHeader;
            this.remainingHeader = remainingHeader;
            this.resetHeader = resetHeader;
        }

        public boolean isAllowed() { return allowed; }

        @Override
        public String toString() {
            return String.format("Allowed: %-5s | Limit: %d | Rem: %d | Reset: %d",
                    allowed, limitHeader, remainingHeader, resetHeader);
        }
    }

    // ====================================================
    // 2. STRATEGY INTERFACE & IMPLEMENTATIONS
    // ====================================================

    interface BucketStrategy {
        RateLimitResponse tryConsume(RateLimitRule rule);
    }

    // --- ALGO 1: TOKEN BUCKET ---
    // Good for: Allowing bursts.
    static class TokenBucket implements BucketStrategy {
        private double tokens;
        private long lastRefillTimestamp;

        public TokenBucket(int maxCapacity) {
            this.tokens = maxCapacity;
            this.lastRefillTimestamp = System.nanoTime();
        }

        @Override
        public synchronized RateLimitResponse tryConsume(RateLimitRule rule) {
            refill(rule.getRatePerSecond(), rule.getCapacity());
            boolean allowed = false;
            if (tokens >= 1) {
                tokens -= 1;
                allowed = true;
            }
            long resetTime = Instant.now().getEpochSecond() + rule.getTimeWindowSeconds();
            return new RateLimitResponse(allowed, rule.getCapacity(), (long) tokens, resetTime);
        }

        private void refill(double tokensPerSecond, int maxCapacity) {
            long now = System.nanoTime();
            long deltaNano = now - lastRefillTimestamp;
            double tokensToAdd = (deltaNano / 1_000_000_000.0) * tokensPerSecond;
            if (tokensToAdd > 0) {
                tokens = Math.min(maxCapacity, tokens + tokensToAdd);
                lastRefillTimestamp = now;
            }
        }
    }

    static class LockFreeTokenBucket implements BucketStrategy {
        private final AtomicReference<State> state;

        // Immutable state object for AtomicReference
        static class State {
            final double tokens;
            final long lastRefillTimestamp;

            State(double tokens, long lastRefillTimestamp) {
                this.tokens = tokens;
                this.lastRefillTimestamp = lastRefillTimestamp;
            }
        }

        public LockFreeTokenBucket(int maxCapacity) {
            this.state = new AtomicReference<>(new State(maxCapacity, System.nanoTime()));
        }

        @Override
        public RateLimitResponse tryConsume(RateLimitRule rule) {
            // CAS Loop
            while (true) {
                State current = state.get();
                long now = System.nanoTime();

                // 1. Calculate Refill
                long deltaNano = now - current.lastRefillTimestamp;
                double tokensToAdd = (deltaNano / 1_000_000_000.0) * rule.getRatePerSecond();
                double newTokens = Math.min(rule.getCapacity(), current.tokens + tokensToAdd);

                // 2. Decide
                boolean allowed = false;
                double finalTokens = newTokens;

                if (newTokens >= 1) {
                    finalTokens = newTokens - 1;
                    allowed = true;
                }

                // 3. Attempt State Update
                State next = new State(finalTokens, now);
                if (state.compareAndSet(current, next)) {
                    long resetTime = Instant.now().getEpochSecond() + rule.getTimeWindowSeconds();
                    return new RateLimitResponse(allowed, rule.getCapacity(), (long) finalTokens, resetTime);
                }
                // If compareAndSet fails, we loop and try again with updated values
                Thread.onSpinWait();
            }
        }
    }

    // --- ALGO 2: LEAKY BUCKET ---
    // Good for: Smoothing out traffic (constant outflow).
    static class LeakyBucket implements BucketStrategy {
        private double currentWaterLevel;
        private long lastLeakTimestamp;

        public LeakyBucket(int capacity) {
            this.currentWaterLevel = 0;
            this.lastLeakTimestamp = System.nanoTime();
        }

        @Override
        public synchronized RateLimitResponse tryConsume(RateLimitRule rule) {
            leak(rule.getRatePerSecond());
            boolean allowed = false;
            if (currentWaterLevel + 1 <= rule.getCapacity()) {
                currentWaterLevel += 1;
                allowed = true;
            }
            long remainingSpace = (long)(rule.getCapacity() - currentWaterLevel);
            long resetTime = Instant.now().getEpochSecond();
            return new RateLimitResponse(allowed, rule.getCapacity(), remainingSpace, resetTime);
        }

        private void leak(double leakRatePerSecond) {
            long now = System.nanoTime();
            long deltaNano = now - lastLeakTimestamp;
            double waterLeaked = (deltaNano / 1_000_000_000.0) * leakRatePerSecond;
            if (waterLeaked > 0) {
                currentWaterLevel = Math.max(0, currentWaterLevel - waterLeaked);
                lastLeakTimestamp = now;
            }
        }
    }

    // --- ALGO 3: SLIDING WINDOW LOG ---
    // Good for: 100% Accuracy.
    // Bad for: Memory usage (O(N) history storage).
    static class SlidingWindowLog implements BucketStrategy {
        private final Deque<Long> requestLog = new ArrayDeque<>();

        @Override
        public synchronized RateLimitResponse tryConsume(RateLimitRule rule) {
            long nowNano = System.nanoTime();
            long windowNano = rule.getTimeWindowSeconds() * 1_000_000_000L;

            // 1. Evict timestamps older than the window
            while (!requestLog.isEmpty() && (nowNano - requestLog.peekFirst() > windowNano)) {
                requestLog.pollFirst();
            }

            // 2. Check capacity
            boolean allowed = false;
            if (requestLog.size() < rule.getCapacity()) {
                requestLog.addLast(nowNano);
                allowed = true;
            }

            long remaining = rule.getCapacity() - requestLog.size();
            // Reset time is roughly when the oldest log expires
            long oldestEntry = requestLog.isEmpty() ? nowNano : requestLog.peekFirst();
            long resetTime = Instant.now().getEpochSecond() +
                    ((windowNano - (nowNano - oldestEntry)) / 1_000_000_000L);

            return new RateLimitResponse(allowed, rule.getCapacity(), remaining, resetTime);
        }
    }

    // --- ALGO 6: SEMAPHORE TOKEN BUCKET (Active Refill) ---
    // Good for: Simplicity using OS primitives.
    // Note: Requires a background scheduler, unlike the Lazy approaches.
    static class SemaphoreTokenBucket implements BucketStrategy {
        private final Semaphore semaphore;
        private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

        public SemaphoreTokenBucket(int maxCapacity, int refillRatePerSecond) {
            this.semaphore = new Semaphore(maxCapacity);

            // Active Refill Task
            long periodNanos = 1_000_000_000L / refillRatePerSecond;
            scheduler.scheduleAtFixedRate(() -> {
                if (semaphore.availablePermits() < maxCapacity) {
                    semaphore.release();
                }
            }, periodNanos, periodNanos, TimeUnit.NANOSECONDS);
        }

        @Override
        public RateLimitResponse tryConsume(RateLimitRule rule) {
            boolean allowed = semaphore.tryAcquire();

            // Estimation for headers (Since Semaphore doesn't track time-to-reset natively)
            long remaining = semaphore.availablePermits();
            long resetTime = Instant.now().getEpochSecond() + 1; // Simplified for active refill

            return new RateLimitResponse(allowed, rule.getCapacity(), remaining, resetTime);
        }
    }

    // --- ALGO 4: SLIDING WINDOW COUNTER (Hybrid) ---
    // Good for: Approximation of sliding window with low memory (O(1)).
    // Logic: Weighted average of Previous Window and Current Window.
    static class SlidingWindowCounter implements BucketStrategy {
        private int prevWindowCount = 0;
        private int currWindowCount = 0;
        private long currWindowStartSec = 0;

        @Override
        public synchronized RateLimitResponse tryConsume(RateLimitRule rule) {
            long nowSec = Instant.now().getEpochSecond();
            long windowSize = rule.getTimeWindowSeconds();

            // Calculate the current window key
            long currentWindowKey = (nowSec / windowSize) * windowSize;

            // Handle window rollover
            if (currentWindowKey > currWindowStartSec) {
                prevWindowCount = currWindowCount;
                currWindowCount = 0;
                currWindowStartSec = currentWindowKey;
            }

            // Estimate the total count
            double estimatedCount = prevWindowCount + currWindowCount;

            // Check if the request is allowed
            boolean allowed = estimatedCount < rule.getCapacity();
            if (allowed) {
                currWindowCount++;
            }

            long remaining = Math.max(0, rule.getCapacity() - (long) estimatedCount);
            long resetTime = currWindowStartSec + windowSize;

            return new RateLimitResponse(allowed, rule.getCapacity(), remaining, resetTime);}
    }

    // ====================================================
    // 3. FACTORY & MANAGER
    // ====================================================

    static class BucketFactory {
        public static BucketStrategy createBucket(RateLimitRule rule) {
            return switch (rule.algorithm) {
                case LOCK_FREE_TOKEN_BUCKET -> new LockFreeTokenBucket(rule.getCapacity());
                case SEMAPHORE_TOKEN_BUCKET -> new SemaphoreTokenBucket(rule.getCapacity(), rule.requestsPerUnit);
                case SLIDING_WINDOW_LOG -> new SlidingWindowLog();
                case SLIDING_WINDOW_COUNTER -> new SlidingWindowCounter();
                case LEAKY_BUCKET -> new LeakyBucket(rule.getCapacity());
                default -> new TokenBucket(rule.getCapacity());
            };
        }
    }

    public static class RateLimiterService {
        private final Map<String, BucketStrategy> bucketStorage = new ConcurrentHashMap<>();
        private final Map<String, RateLimitRule> clientRules = new ConcurrentHashMap<>();

        public void provisionClient(String clientId, RateLimitRule rule) {
            clientRules.put(clientId, rule);
            bucketStorage.remove(clientId); // Reset bucket on config change
        }

        public RateLimitResponse handleRequest(String clientId) {
            RateLimitRule rule = clientRules.get(clientId);
            if (rule == null) return new RateLimitResponse(false, 0, 0, 0);

            BucketStrategy bucket = bucketStorage.computeIfAbsent(clientId, k -> BucketFactory.createBucket(rule));
            return bucket.tryConsume(rule);
        }
    }

    // ====================================================
    // 4. TEST HARNESS
    // ====================================================

    public static void main(String[] args) throws InterruptedException {
        RateLimiterService service = new RateLimiterService();

        System.out.println("=== Rate Limiter Algorithms Demo ===");

        // 1. TOKEN BUCKET
        System.out.println("\n--- Testing Token Bucket (10/sec) ---");
        service.provisionClient("CLIENT_TB", new RateLimitRule(10, 10, 1, AlgorithmType.TOKEN_BUCKET));
        testBurst(service, "CLIENT_TB", 12);

        // 2. SLIDING WINDOW LOG
        System.out.println("\n--- Testing Sliding Window Log (5/sec) ---");
        service.provisionClient("CLIENT_SWL", new RateLimitRule(5, 5, 1, AlgorithmType.SLIDING_WINDOW_LOG));
        testBurst(service, "CLIENT_SWL", 7);

        // 3. SLIDING WINDOW COUNTER
        System.out.println("\n--- Testing Sliding Window Counter (5/sec) ---");
        service.provisionClient("CLIENT_SWC", new RateLimitRule(5, 5, 1, AlgorithmType.SLIDING_WINDOW_COUNTER));
        testBurst(service, "CLIENT_SWC", 7);

        // 4. Lock free
        System.out.println("\n--- Testing Sliding Window Counter (5/sec) ---");
        service.provisionClient("CLIENT_SWC", new RateLimitRule(5, 5, 1, AlgorithmType.LOCK_FREE_TOKEN_BUCKET));
        testBurst(service, "CLIENT_SWC", 30);
    }

    private static void testBurst(RateLimiterService service, String client, int requests) {
        for (int i = 0; i < requests; i++) {
            RateLimitResponse res = service.handleRequest(client);
            System.out.println(String.format("Req %2d | %s", i + 1, res));
            try { Thread.sleep(50); } catch (InterruptedException e) {} // Slight delay for logs
        }
    }
}