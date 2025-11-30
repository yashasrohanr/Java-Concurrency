package org.example;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/* ================= ENUMS ================= */
enum SeatStatus { AVAILABLE, HOLD, BOOKED }

/* ================= MODELS ================= */
class Movie {
    String id, title;
    Movie(String id, String title) { this.id = id; this.title = title; }
}

class Screen {
    String id;
    int rows, cols;
    Screen(String id, int rows, int cols) { this.id = id; this.rows = rows; this.cols = cols; }
}

class Seat {
    String id;
    volatile SeatStatus status = SeatStatus.AVAILABLE;
    volatile String lockedBy = null;
    volatile long lockExpiry = 0;

    Seat(String id) { this.id = id; }

    synchronized boolean tryLock(String session) {
        if (status == SeatStatus.AVAILABLE) {
            status = SeatStatus.HOLD;
            lockedBy = session;
            lockExpiry = System.currentTimeMillis() + 5 * 60 * 1000; // 5 mins
            return true;
        }
        return false;
    }

    synchronized void unlock(String session) {
        if (status == SeatStatus.HOLD && session.equals(lockedBy)) {
            status = SeatStatus.AVAILABLE;
            lockedBy = null;
        }
    }

    synchronized void book(String session) {
        if (status == SeatStatus.HOLD && session.equals(lockedBy))
            status = SeatStatus.BOOKED;
    }

    synchronized void expireIfNeeded() {
        if (status == SeatStatus.HOLD && System.currentTimeMillis() > lockExpiry)
            unlock(lockedBy);
    }
}

class Show {
    String id;
    Movie movie;
    Screen screen;
    Map<String, Seat> seats = new ConcurrentHashMap<>();

    Show(String id, Movie movie, Screen screen) {
        this.id = id;
        this.movie = movie;
        this.screen = screen;
        for (int r = 1; r <= screen.rows; r++)
            for (int c = 1; c <= screen.cols; c++)
                seats.put("R"+r+"C"+c, new Seat("R"+r+"C"+c));
    }
}

class Session {
    String id, user;
    Show show;
    long expiry;
    List<String> seatIds = new ArrayList<>();

    Session(String id, String user, Show show) {
        this.id = id;
        this.user = user;
        this.show = show;
        this.expiry = System.currentTimeMillis() + 5 * 60 * 1000; // 5 mins
    }

    boolean expired() { return System.currentTimeMillis() > expiry; }
}

/* ================= SERVICE ================= */
class BookingService {
    Map<String, Show> shows = new ConcurrentHashMap<>();
    Map<String, Session> sessions = new ConcurrentHashMap<>();

    void addShow(Show show) { shows.put(show.id, show); }

    Session createSession(String user, String showId) {
        Show s = shows.get(showId);
        if (s == null) throw new RuntimeException("Show not found");
        Session session = new Session(UUID.randomUUID().toString(), user, s);
        sessions.put(session.id, session);
        return session;
    }

    List<String> getAvailableSeats(String showId) {
        Show s = shows.get(showId);
        List<String> av = new ArrayList<>();
        for (Seat seat : s.seats.values()) {
            seat.expireIfNeeded();
            if (seat.status == SeatStatus.AVAILABLE) av.add(seat.id);
        }
        return av;
    }

    boolean selectSeats(String sessionId, List<String> ids) {
        Session session = sessions.get(sessionId);
        if (session == null || session.expired()) return false;

        // Lock all seats atomically (best-effort rollback)
        List<Seat> locked = new ArrayList<>();
        for (String id : ids) {
            Seat seat = session.show.seats.get(id);
            if (seat == null || !seat.tryLock(session.id)) {
                for (Seat s : locked) s.unlock(session.id);
                return false;
            }
            locked.add(seat);
            session.seatIds.add(id);
        }
        return true;
    }

    boolean confirmBooking(String sessionId) {
        Session session = sessions.get(sessionId);
        if (session == null || session.expired()) { cleanup(session); return false; }

        for (String id : session.seatIds)
            session.show.seats.get(id).book(session.id);

        sessions.remove(sessionId);
        return true;
    }

    void cleanup(Session session) {
        if (session == null) return;
        for (String id : session.seatIds)
            session.show.seats.get(id).unlock(session.id);
        sessions.remove(session.id);
    }

    void runCleanup() {
        for (Session s : sessions.values())
            if (s.expired()) cleanup(s);
        for (Show show : shows.values())
            for (Seat seat : show.seats.values())
                seat.expireIfNeeded();
    }
}

/* ================= DEMO ================= */
public class MovieBookingMini {
    public static void main(String[] args) throws Exception {
        BookingService service = new BookingService();

        Movie m = new Movie("M1", "Inception");
        Screen s = new Screen("SCR1", 3, 5);
        Show show = new Show("SH1", m, s);
        service.addShow(show);

        // User1
        Session u1 = service.createSession("user1", "SH1");
        service.selectSeats(u1.id, List.of("R1C1", "R1C2"));
        service.confirmBooking(u1.id);

        // User2 trying same seats
        Session u2 = service.createSession("user2", "SH1");
        boolean ok = service.selectSeats(u2.id, List.of("R1C1"));
        System.out.println("User2 lock success? " + ok); // false

        service.runCleanup();
    }
}
