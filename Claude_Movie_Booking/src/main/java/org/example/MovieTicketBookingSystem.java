package org.example;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

// ==================== ENUMS ====================
enum SeatStatus {
    AVAILABLE, TEMPORARILY_UNAVAILABLE, BOOKED, BLOCKED
}

enum PaymentStatus {
    PENDING, COMPLETED, FAILED, CANCELLED
}

// ==================== ENTITIES ====================
class Movie {
    private final String id;
    private final String title;
    private final String description;

    public Movie(String id, String title, String description) {
        this.id = id;
        this.title = title;
        this.description = description;
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
}

class Screen {
    private final String id;
    private final int rows;
    private final int seatsPerRow;

    public Screen(String id, int rows, int seatsPerRow) {
        this.id = id;
        this.rows = rows;
        this.seatsPerRow = seatsPerRow;
    }

    public String getId() { return id; }
    public int getRows() { return rows; }
    public int getSeatsPerRow() { return seatsPerRow; }
}

class Seat {
    private final String id;
    private final int row;
    private final int column;
    private volatile SeatStatus status;
    private volatile String lockedBy;
    private volatile long lockTime;

    public Seat(String id, int row, int column) {
        this.id = id;
        this.row = row;
        this.column = column;
        this.status = SeatStatus.AVAILABLE;
    }

    public String getId() { return id; }
    public int getRow() { return row; }
    public int getColumn() { return column; }
    public synchronized SeatStatus getStatus() { return status; }
    public synchronized String getLockedBy() { return lockedBy; }
    public synchronized long getLockTime() { return lockTime; }

    public synchronized boolean tryLock(String sessionId, long lockDurationMs) {
        if (status == SeatStatus.AVAILABLE) {
            this.status = SeatStatus.TEMPORARILY_UNAVAILABLE;
            this.lockedBy = sessionId;
            this.lockTime = System.currentTimeMillis() + lockDurationMs;
            return true;
        }
        return false;
    }

    public synchronized void unlock(String sessionId) {
        if (sessionId.equals(this.lockedBy) && status == SeatStatus.TEMPORARILY_UNAVAILABLE) {
            this.status = SeatStatus.AVAILABLE;
            this.lockedBy = null;
            this.lockTime = 0;
        }
    }

    public synchronized void book(String sessionId) {
        if (sessionId.equals(this.lockedBy) && status == SeatStatus.TEMPORARILY_UNAVAILABLE) {
            this.status = SeatStatus.BOOKED;
        }
    }

    public synchronized void releaseLockIfExpired() {
        if (status == SeatStatus.TEMPORARILY_UNAVAILABLE &&
                System.currentTimeMillis() > lockTime) {
            this.status = SeatStatus.AVAILABLE;
            this.lockedBy = null;
            this.lockTime = 0;
        }
    }
}

class Show {
    private final String id;
    private final Movie movie;
    private final Screen screen;
    private final LocalDateTime startTime;
    private final Map<String, Seat> seats;

    public Show(String id, Movie movie, Screen screen, LocalDateTime startTime) {
        this.id = id;
        this.movie = movie;
        this.screen = screen;
        this.startTime = startTime;
        this.seats = initializeSeats(screen);
    }

    private Map<String, Seat> initializeSeats(Screen screen) {
        Map<String, Seat> seatMap = new ConcurrentHashMap<>();
        for (int i = 0; i < screen.getRows(); i++) {
            for (int j = 0; j < screen.getSeatsPerRow(); j++) {
                String seatId = String.format("R%dC%d", i + 1, j + 1);
                seatMap.put(seatId, new Seat(seatId, i + 1, j + 1));
            }
        }
        return seatMap;
    }

    public String getId() { return id; }
    public Movie getMovie() { return movie; }
    public Screen getScreen() { return screen; }
    public LocalDateTime getStartTime() { return startTime; }
    public Seat getSeat(String seatId) { return seats.get(seatId); }
    public Collection<Seat> getAllSeats() { return seats.values(); }
}

class BookingSession {
    private final String id;
    private final String userId;
    private final Show show;
    private final List<String> lockedSeats;
    private final long createdAt;
    private final long expiresAt;
    private volatile PaymentStatus paymentStatus;

    public BookingSession(String id, String userId, Show show, long sessionTimeoutMs) {
        this.id = id;
        this.userId = userId;
        this.show = show;
        this.lockedSeats = Collections.synchronizedList(new ArrayList<>());
        this.createdAt = System.currentTimeMillis();
        this.expiresAt = createdAt + sessionTimeoutMs;
        this.paymentStatus = PaymentStatus.PENDING;
    }

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public Show getShow() { return show; }
    public List<String> getLockedSeats() { return new ArrayList<>(lockedSeats); }
    public PaymentStatus getPaymentStatus() { return paymentStatus; }
    public boolean isExpired() { return System.currentTimeMillis() > expiresAt; }

    public void addLockedSeat(String seatId) {
        lockedSeats.add(seatId);
    }

    public void setPaymentStatus(PaymentStatus status) {
        this.paymentStatus = status;
    }
}

class Booking {
    private final String id;
    private final String userId;
    private final Show show;
    private final List<Seat> seats;
    private final double totalPrice;
    private final LocalDateTime bookedAt;

    public Booking(String id, String userId, Show show, List<Seat> seats, double totalPrice) {
        this.id = id;
        this.userId = userId;
        this.show = show;
        this.seats = seats;
        this.totalPrice = totalPrice;
        this.bookedAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public Show getShow() { return show; }
    public List<Seat> getSeats() { return seats; }
    public double getTotalPrice() { return totalPrice; }
    public LocalDateTime getBookedAt() { return bookedAt; }
}

// ==================== SERVICES ====================
class SeatLockManager {
    private static final long LOCK_DURATION_MS = 5 * 60 * 1000; // 5 minutes

    public synchronized boolean lockSeats(Show show, List<String> seatIds, String sessionId) {
        for (String seatId : seatIds) {
            Seat seat = show.getSeat(seatId);
            if (seat == null || !seat.tryLock(sessionId, LOCK_DURATION_MS)) {
                // Rollback: unlock all previously locked seats
                for (String lockedSeatId : seatIds) {
                    Seat lockedSeat = show.getSeat(lockedSeatId);
                    if (lockedSeat != null) {
                        lockedSeat.unlock(sessionId);
                    }
                }
                return false;
            }
        }
        return true;
    }

    public void unlockSeats(Show show, List<String> seatIds, String sessionId) {
        for (String seatId : seatIds) {
            Seat seat = show.getSeat(seatId);
            if (seat != null) {
                seat.unlock(sessionId);
            }
        }
    }

    public void releaseExpiredLocks(Show show) {
        for (Seat seat : show.getAllSeats()) {
            seat.releaseLockIfExpired();
        }
    }

    public void confirmSeats(Show show, List<String> seatIds, String sessionId) {
        for (String seatId : seatIds) {
            Seat seat = show.getSeat(seatId);
            if (seat != null) {
                seat.book(sessionId);
            }
        }
    }
}

class PaymentService {
    public boolean processPayment(String bookingId, double amount) {
        // Simulate payment processing - can integrate with external payment gateway
        try {
            Thread.sleep(1000);
            return Math.random() > 0.1; // 90% success rate for simulation
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}

class BookingService {
    private final ConcurrentHashMap<String, BookingSession> activeSessions;
    private final ConcurrentHashMap<String, Booking> bookings;
    private final ConcurrentHashMap<String, Show> shows;
    private final SeatLockManager lockManager;
    private final PaymentService paymentService;
    private final long sessionTimeoutMs;
    private final ScheduledExecutorService scheduler;

    public BookingService(long sessionTimeoutMs) {
        this.activeSessions = new ConcurrentHashMap<>();
        this.bookings = new ConcurrentHashMap<>();
        this.shows = new ConcurrentHashMap<>();
        this.lockManager = new SeatLockManager();
        this.paymentService = new PaymentService();
        this.sessionTimeoutMs = sessionTimeoutMs;
        this.scheduler = Executors.newScheduledThreadPool(2);
        startSessionCleanupTask();
    }

    private void startSessionCleanupTask() {
        scheduler.scheduleAtFixedRate(() -> {
            List<String> expiredSessions = activeSessions.entrySet().stream()
                    .filter(e -> e.getValue().isExpired())
                    .map(Map.Entry::getKey)
                    .toList();

            for (String sessionId : expiredSessions) {
                cancelSession(sessionId);
            }

            // Release expired seat locks
            for (Show show : shows.values()) {
                lockManager.releaseExpiredLocks(show);
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    public void addShow(Show show) {
        shows.put(show.getId(), show);
    }

    public Show getShow(String showId) {
        return shows.get(showId);
    }

    public List<Seat> getAvailableSeats(String showId) {
        Show show = shows.get(showId);
        if (show == null) return Collections.emptyList();

        return show.getAllSeats().stream()
                .filter(s -> s.getStatus() == SeatStatus.AVAILABLE)
                .collect(Collectors.toList());
    }

    public BookingSession createSession(String userId, String showId) {
        Show show = shows.get(showId);
        if (show == null) {
            throw new IllegalArgumentException("Show not found");
        }

        String sessionId = UUID.randomUUID().toString();
        BookingSession session = new BookingSession(sessionId, userId, show, sessionTimeoutMs);
        activeSessions.put(sessionId, session);
        return session;
    }

    public boolean selectSeats(String sessionId, List<String> seatIds) {
        BookingSession session = activeSessions.get(sessionId);
        if (session == null || session.isExpired()) {
            throw new IllegalStateException("Session not found or expired");
        }

        Show show = session.getShow();
        if (!lockManager.lockSeats(show, seatIds, sessionId)) {
            return false;
        }

        for (String seatId : seatIds) {
            session.addLockedSeat(seatId);
        }
        return true;
    }

    public boolean completeBooking(String sessionId, String paymentMethodId) {
        BookingSession session = activeSessions.get(sessionId);
        if (session == null || session.isExpired()) {
            throw new IllegalStateException("Session not found or expired");
        }

        if (session.getLockedSeats().isEmpty()) {
            throw new IllegalStateException("No seats selected");
        }

        String bookingId = UUID.randomUUID().toString();
        double totalPrice = session.getLockedSeats().size() * 10.0; // $10 per seat

        // Process payment
        if (!paymentService.processPayment(bookingId, totalPrice)) {
            session.setPaymentStatus(PaymentStatus.FAILED);
            lockManager.unlockSeats(session.getShow(), session.getLockedSeats(), sessionId);
            return false;
        }

        session.setPaymentStatus(PaymentStatus.COMPLETED);

        // Confirm seats as booked
        Show show = session.getShow();
        List<Seat> bookedSeats = session.getLockedSeats().stream()
                .map(show::getSeat)
                .collect(Collectors.toList());

        lockManager.confirmSeats(show, session.getLockedSeats(), sessionId);

        // Create booking record
        Booking booking = new Booking(bookingId, session.getUserId(), show, bookedSeats, totalPrice);
        bookings.put(bookingId, booking);

        // Clean up session
        activeSessions.remove(sessionId);

        return true;
    }

    public void cancelSession(String sessionId) {
        BookingSession session = activeSessions.remove(sessionId);
        if (session != null) {
            lockManager.unlockSeats(session.getShow(), session.getLockedSeats(), sessionId);
        }
    }

    public Booking getBooking(String bookingId) {
        return bookings.get(bookingId);
    }

    public void shutdown() {
        scheduler.shutdown();
    }
}

// ==================== DEMO ====================
public class MovieTicketBookingSystem {
    public static void main(String[] args) throws InterruptedException {
        BookingService bookingService = new BookingService(5 * 60 * 1000); // 5-minute timeout

        // Setup: Create movies, screens, and shows
        Movie movie = new Movie("M1", "Inception", "A sci-fi thriller");
        Screen screen = new Screen("S1", 5, 8); // 5 rows, 8 seats per row
        Show show = new Show("SHOW1", movie, screen, LocalDateTime.now().plusDays(1));
        bookingService.addShow(show);

        // Simulate concurrent booking attempts
        ExecutorService executor = Executors.newFixedThreadPool(3);

        // User 1: Browse and book seats
        executor.submit(() -> {
            try {
                BookingSession session = bookingService.createSession("user1", "SHOW1");
                System.out.println("User1 session: " + session.getId());

                List<Seat> available = bookingService.getAvailableSeats("SHOW1");
                List<String> selectedSeats = available.stream()
                        .limit(2)
                        .map(Seat::getId)
                        .collect(Collectors.toList());

                if (bookingService.selectSeats(session.getId(), selectedSeats)) {
                    Thread.sleep(2000);
                    if (bookingService.completeBooking(session.getId(), "CC_VISA")) {
                        System.out.println("User1 booking successful!");
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // User 2: Concurrent booking attempt
        executor.submit(() -> {
            try {
                BookingSession session = bookingService.createSession("user2", "SHOW1");
                List<Seat> available = bookingService.getAvailableSeats("SHOW1");
                List<String> selectedSeats = available.stream()
                        .limit(3)
                        .map(Seat::getId)
                        .collect(Collectors.toList());

                if (bookingService.selectSeats(session.getId(), selectedSeats)) {
                    Thread.sleep(1000);
                    if (bookingService.completeBooking(session.getId(), "CC_AMEX")) {
                        System.out.println("User2 booking successful!");
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // User 3: Session timeout test
        executor.submit(() -> {
            try {
                BookingSession session = bookingService.createSession("user3", "SHOW1");
                List<Seat> available = bookingService.getAvailableSeats("SHOW1");
                List<String> selectedSeats = available.stream()
                        .limit(2)
                        .map(Seat::getId)
                        .collect(Collectors.toList());

                if (bookingService.selectSeats(session.getId(), selectedSeats)) {
                    System.out.println("User3 reserved seats but won't complete booking (testing timeout)");
                    Thread.sleep(10000); // Long wait to trigger timeout
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        executor.shutdown();
        executor.awaitTermination(20, TimeUnit.SECONDS);

        Thread.sleep(35000); // Wait for cleanup to run
        System.out.println("Available seats remaining: " + bookingService.getAvailableSeats("SHOW1").size());

        bookingService.shutdown();
    }
}