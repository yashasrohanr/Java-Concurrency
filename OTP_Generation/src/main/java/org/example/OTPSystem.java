package org.example;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OTP System LLD - SDE3 Level
 *
 * Concepts Implemented:
 * 1. Strategy Pattern for OTP generation + OTP sending.
 * 2. Rate Limiting (per minute, hour, day).
 * 3. Thread-safe Repository with TTL Expiry Worker.
 * 4. OTP stored in hashed form + one-time use.
 * 5. Facade (OTPManager) coordinating flow.
 */

// =============================
// 1. STRATEGY PATTERN: OTP Generation
// =============================

interface OTPGenerationStrategy {
    String generateOTP(int length);
}

class NumericOTPStrategy implements OTPGenerationStrategy {
    private static final SecureRandom random = new SecureRandom();

    @Override
    public String generateOTP(int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(random.nextInt(10));
        }
        return sb.toString();
    }
}

class AlphaNumericOTPStrategy implements OTPGenerationStrategy {
    private static final SecureRandom random = new SecureRandom();
    private static final char[] chars =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();

    @Override
    public String generateOTP(int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(chars[random.nextInt(chars.length)]);
        }
        return sb.toString();
    }
}

// =============================
// 2. STRATEGY PATTERN: OTP Sending
// =============================

interface OTPSendStrategy {
    void send(String userId, String otp);
}

class SMSSendStrategy implements OTPSendStrategy {
    @Override
    public void send(String userId, String otp) {
        System.out.println("[SMS] Sending OTP " + otp + " to " + userId);
    }
}

class EmailSendStrategy implements OTPSendStrategy {
    @Override
    public void send(String userId, String otp) {
        System.out.println("[Email] Sending OTP " + otp + " to " + userId);
    }
}

// =============================
// 3. OTP MODEL
// =============================

class OTPRecord {
    String hashedOtp;
    long expiryEpoch;
    boolean used;

    public OTPRecord(String hashedOtp, long expiryEpoch) {
        this.hashedOtp = hashedOtp;
        this.expiryEpoch = expiryEpoch;
        this.used = false;
    }
}

// =============================
// 4. THREAD-SAFE OTP REPOSITORY WITH TTL
// =============================

class OTPRepository {
    private final Map<String, OTPRecord> store = new ConcurrentHashMap<>();

    public void save(String userId, OTPRecord record) {
        store.put(userId, record);
    }

    public OTPRecord get(String userId) {
        return store.get(userId);
    }

    public void delete(String userId) {
        store.remove(userId);
    }

    public void cleanupExpired() {
        long now = Instant.now().getEpochSecond();
        for (String user : store.keySet()) {
            OTPRecord r = store.get(user);
            if (r == null) continue;
            if (r.expiryEpoch < now) {
                store.remove(user);
            }
        }
    }
}

// =============================
// 5. RATE LIMITER
// =============================

class OTPRequestRateLimiter {

    private static class Counters {
        AtomicInteger perMin = new AtomicInteger(0);
        AtomicInteger perHour = new AtomicInteger(0);
        AtomicInteger perDay = new AtomicInteger(0);
        long lastResetMin = System.currentTimeMillis();
        long lastResetHour = System.currentTimeMillis();
        long lastResetDay = System.currentTimeMillis();
    }

    // userId → counters
    private final Map<String, Counters> rateMap = new ConcurrentHashMap<>();

    // thresholds
    private final int maxPerMin;
    private final int maxPerHour;
    private final int maxPerDay;

    public OTPRequestRateLimiter(int maxPerMin, int maxPerHour, int maxPerDay) {
        this.maxPerMin = maxPerMin;
        this.maxPerHour = maxPerHour;
        this.maxPerDay = maxPerDay;
    }

    public boolean allow(String userId) {
        Counters c = rateMap.computeIfAbsent(userId, k -> new Counters());
        long now = System.currentTimeMillis();

        // reset windows if time passed
        if (now - c.lastResetMin > 60_000) {
            c.perMin.set(0);
            c.lastResetMin = now;
        }
        if (now - c.lastResetHour > 3600_000) {
            c.perHour.set(0);
            c.lastResetHour = now;
        }
        if (now - c.lastResetDay > 86_400_000) {
            c.perDay.set(0);
            c.lastResetDay = now;
        }

        if (c.perMin.get() >= maxPerMin) return false;
        if (c.perHour.get() >= maxPerHour) return false;
        if (c.perDay.get() >= maxPerDay) return false;

        // increment counters
        c.perMin.incrementAndGet();
        c.perHour.incrementAndGet();
        c.perDay.incrementAndGet();

        return true;
    }
}

// =============================
// 6. UTILS – HASH + CONSTANT TIME COMPARE
// =============================

class OTPUtils {

    public static String hash(String input) {
        return Integer.toHexString(input.hashCode()); // Interview: replace with SHA-256 normally
    }

    public static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int res = 0;
        for (int i = 0; i < a.length(); i++) {
            res |= a.charAt(i) ^ b.charAt(i);
        }
        return res == 0;
    }
}

// =============================
// 7. FACADE: OTP MANAGER
// =============================

class OTPService {

    private final OTPGenerationStrategy generationStrategy;
    private final OTPSendStrategy sendStrategy;
    private final OTPRepository repository;
    private final OTPRequestRateLimiter rateLimiter;

    private final ScheduledExecutorService ttlCleaner = Executors.newScheduledThreadPool(1);

    public OTPService(
            OTPGenerationStrategy generationStrategy,
            OTPSendStrategy sendStrategy,
            OTPRepository repository,
            OTPRequestRateLimiter rateLimiter
    ) {
        this.generationStrategy = generationStrategy;
        this.sendStrategy = sendStrategy;
        this.repository = repository;
        this.rateLimiter = rateLimiter;

        ttlCleaner.scheduleAtFixedRate(repository::cleanupExpired, 10, 10, TimeUnit.SECONDS);
    }

    public String generateAndSendOTP(String userId, int length, int ttlSeconds) {

        if (!rateLimiter.allow(userId)) {
            throw new RuntimeException("Rate limit exceeded for user " + userId);
        }

        String otp = generationStrategy.generateOTP(length);
        String hash = OTPUtils.hash(otp);

        OTPRecord record = new OTPRecord(
                hash,
                Instant.now().getEpochSecond() + ttlSeconds
        );

        repository.save(userId, record);

        sendStrategy.send(userId, otp);

        return otp; // interviewer-friendly
    }

    public boolean validateOTP(String userId, String otpInput) {
        OTPRecord record = repository.get(userId);
        if (record == null) return false;

        long now = Instant.now().getEpochSecond();
        if (record.expiryEpoch < now) {
            repository.delete(userId);
            return false;
        }

        if (record.used) {
            return false;
        }

        boolean valid =
                OTPUtils.constantTimeEquals(record.hashedOtp, OTPUtils.hash(otpInput));

        if (valid) {
            record.used = true;
            repository.delete(userId); // one-time use
        }

        return valid;
    }

    public void endOperation() {
        ttlCleaner.shutdown();
    }
}

// =============================
// 8. MAIN (DEMO)
// =============================

public class OTPSystem {
    public static void main(String[] args) throws InterruptedException {

        OTPService otpService = new OTPService(
                new NumericOTPStrategy(),
                new SMSSendStrategy(),
                new OTPRepository(),
                new OTPRequestRateLimiter(3, 10, 20)  // per min, hour, day
        );

        System.out.println("--- Generating OTP ---");
        String otp = otpService.generateAndSendOTP("user123", 6, 30);

        System.out.println("--- Validating OTP ---");
        boolean isValid = otpService.validateOTP("user123", otp);
        System.out.println("OTP Valid: " + isValid);

        System.out.println("--- Reuse Attempt ---");
        boolean again = otpService.validateOTP("user123", otp);
        System.out.println("OTP Valid Again: " + again);

        otpService.endOperation();
    }
}
