package org.example;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

// --- Enums ---
enum VehicleType { BIKE, CAR, TRUCK }
enum SpotType { MOTORCYCLE, COMPACT, LARGE, EV }
enum SpotStatus { AVAILABLE, OCCUPIED }
enum PaymentMethod { CASH, CARD, UPI }

// --- Vehicles ---
abstract class Vehicle {
    private final String plate;
    private final VehicleType type;
    public Vehicle(String plate, VehicleType type) { this.plate = plate; this.type = type; }
    public String getPlate() { return plate; }
    public VehicleType getType() { return type; }
}

class Bike extends Vehicle { public Bike(String p) { super(p, VehicleType.BIKE); } }
class Car extends Vehicle { public Car(String p) { super(p, VehicleType.CAR); } }
class Truck extends Vehicle { public Truck(String p) { super(p, VehicleType.TRUCK); } }

// --- Parking Spot ---
class ParkingSpot {
    private final String id;
    private final SpotType type;
    private volatile SpotStatus status;
    private final ReentrantLock lock = new ReentrantLock();
    private Vehicle occupant;

    public ParkingSpot(String id, SpotType type) {
        this.id = id; this.type = type; this.status = SpotStatus.AVAILABLE;
    }

    // Try to occupy spot; returns true only if successful
    public boolean tryAssign(Vehicle v) {
        if (!lock.tryLock()) return false;
        try {
            if (status == SpotStatus.AVAILABLE && canFit(v)) {
                occupant = v;
                status = SpotStatus.OCCUPIED;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    // Free spot (should be called while holding no external lock)
    public boolean free() {
        lock.lock();
        try {
            if (status == SpotStatus.OCCUPIED) {
                occupant = null;
                status = SpotStatus.AVAILABLE;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    public boolean canFit(Vehicle v) {
        VehicleType vt = v.getType();
        switch (type) {
            case MOTORCYCLE: return vt == VehicleType.BIKE;
            case COMPACT: return vt == VehicleType.BIKE || vt == VehicleType.CAR;
            case LARGE: return true;
            case EV: return vt == VehicleType.CAR; // simplified: treat EV as car-type
            default: return false;
        }
    }

    public String getId() { return id; }
    public SpotType getType() { return type; }
    public SpotStatus getStatus() { return status; }
    public String toString() { return id + "(" + type + ":" + status + ")"; }
}

// --- Ticket ---
class Ticket {
    private final String id;
    private final Vehicle vehicle;
    private final ParkingSpot spot;
    private final long entryTs;
    private long exitTs;
    private volatile boolean paid;

    public Ticket(String id, Vehicle v, ParkingSpot s) {
        this.id = id; this.vehicle = v; this.spot = s; this.entryTs = System.currentTimeMillis();
    }

    public void markExit() { exitTs = System.currentTimeMillis(); }
    public long getDurationMinutes() {
        long end = (exitTs == 0) ? System.currentTimeMillis() : exitTs;
        return (end - entryTs) / 60000;
    }
    public void markPaid() { paid = true; }
    public boolean isPaid() { return paid; }
    public String getId() { return id; }
    public Vehicle getVehicle() { return vehicle; }
    public ParkingSpot getSpot() { return spot; }
}

// --- Simple Pricing ---
class Pricing {
    public static double fee(Ticket t) {
        long mins = Math.max(1, t.getDurationMinutes()); // at least 1 minute
        double ratePerHour;
        switch (t.getVehicle().getType()) {
            case BIKE: ratePerHour = 5; break;
            case CAR: ratePerHour = 10; break;
            case TRUCK: ratePerHour = 20; break;
            default: ratePerHour = 10;
        }
        double hours = Math.ceil(mins / 60.0);
        return hours * ratePerHour;
    }
}

// --- Payment service (simulated) ---
class PaymentService {
    public boolean process(PaymentMethod method, Ticket ticket, double amount) {
        // Simulate latency
        try { Thread.sleep(30); } catch (InterruptedException ignored) {}
        // Simple success simulation: always succeed
        ticket.markPaid();
        return true;
    }
}

// --- Parking Level: holds queues of available spots by type ---
class ParkingLevel {
    private final int levelNo;
    private final Map<SpotType, ConcurrentLinkedQueue<ParkingSpot>> availableByType = new EnumMap<>(SpotType.class);
    private final List<ParkingSpot> allSpots = new ArrayList<>();

    public ParkingLevel(int levelNo) {
        this.levelNo = levelNo;
        for (SpotType st : SpotType.values()) availableByType.put(st, new ConcurrentLinkedQueue<>());
    }

    public void addSpot(ParkingSpot s) {
        allSpots.add(s);
        availableByType.get(s.getType()).offer(s);
    }

    // Find and reserve a spot for vehicle (returns spot or null)
    public ParkingSpot reserveSpotFor(Vehicle v) {
        // Preferred order: exact fit then larger types
        List<SpotType> preferred = spotPreferenceFor(v.getType());
        for (SpotType st : preferred) {
            ConcurrentLinkedQueue<ParkingSpot> q = availableByType.get(st);
            ParkingSpot sp;
            while ((sp = q.poll()) != null) {
                // try to assign; if fails, somebody else took it — continue
                if (sp.tryAssign(v)) {
                    return sp;
                } else {
                    // assignment failed -> continue (spot might be occupied by concurrent thread)
                    continue;
                }
            }
        }
        return null;
    }

    // Return a spot to availability (after free)
    public void returnSpot(ParkingSpot s) {
        if (s.getStatus() == SpotStatus.AVAILABLE) {
            availableByType.get(s.getType()).offer(s);
        }
    }

    private List<SpotType> spotPreferenceFor(VehicleType vt) {
        switch (vt) {
            case BIKE: return Arrays.asList(SpotType.MOTORCYCLE, SpotType.COMPACT, SpotType.LARGE);
            case CAR: return Arrays.asList(SpotType.COMPACT, SpotType.LARGE, SpotType.EV);
            case TRUCK: return Arrays.asList(SpotType.LARGE);
            default: return Arrays.asList(SpotType.COMPACT, SpotType.LARGE);
        }
    }

    public int levelNumber() { return levelNo; }
    public int availableCount() {
        return allSpots.stream().mapToInt(s -> s.getStatus() == SpotStatus.AVAILABLE ? 1 : 0).sum();
    }
}

// --- ParkingLot (singleton, thread-safe) ---
class ParkingLot {
    private static final ParkingLot INSTANCE = new ParkingLot();
    private final List<ParkingLevel> levels = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, Ticket> activeTickets = new ConcurrentHashMap<>();
    private final AtomicInteger ticketCounter = new AtomicInteger(0);
    private final PaymentService paymentService = new PaymentService();

    private ParkingLot() {}

    public static ParkingLot get() { return INSTANCE; }

    public void addLevel(ParkingLevel l) { levels.add(l); }

    // Park vehicle: iterate levels, attempt to reserve spot
    public Ticket park(Vehicle v) {
        for (ParkingLevel lvl : levels) {
            ParkingSpot sp = lvl.reserveSpotFor(v);
            if (sp != null) {
                String tid = "T-" + ticketCounter.incrementAndGet();
                Ticket ticket = new Ticket(tid, v, sp);
                activeTickets.put(tid, ticket);
                System.out.println("Parked " + v.getPlate() + " at " + sp.getId() + " (ticket=" + tid + ")");
                return ticket;
            }
        }
        System.out.println("No spot available for " + v.getPlate());
        return null;
    }

    // Unpark: requires payment before freeing
    public boolean unpark(String ticketId, PaymentMethod method) {
        Ticket t = activeTickets.get(ticketId);
        if (t == null) {
            System.out.println("Invalid ticket: " + ticketId);
            return false;
        }
        t.markExit();
        double fee = Pricing.fee(t);
        boolean paid = paymentService.process(method, t, fee);
        if (!paid) {
            System.out.println("Payment failed for " + ticketId);
            return false;
        }
        // free spot and return to its level's queue
        ParkingSpot spot = t.getSpot();
        boolean freed = spot.free();
        // find the level containing this spot and return it to availability
        for (ParkingLevel lvl : levels) {
            // micro-optimization avoided: simply return to all levels; harmless because returnSpot will push it back
            lvl.returnSpot(spot);
        }
        activeTickets.remove(ticketId);
        System.out.println("Unparked ticket=" + ticketId + ", fee=" + fee);
        return true;
    }

    public int totalAvailable() {
        return levels.stream().mapToInt(ParkingLevel::availableCount).sum();
    }
}

// --- Demo with concurrent parking ---
public class ParkingLotDemo {
    public static void main(String[] args) throws InterruptedException {
        ParkingLot lot = ParkingLot.get();

        // build 2 levels
        ParkingLevel l0 = new ParkingLevel(0);
        for (int i = 0; i < 5; i++) l0.addSpot(new ParkingSpot("L0-M-" + i, SpotType.MOTORCYCLE));
        for (int i = 0; i < 5; i++) l0.addSpot(new ParkingSpot("L0-C-" + i, SpotType.COMPACT));
        for (int i = 0; i < 2; i++) l0.addSpot(new ParkingSpot("L0-L-" + i, SpotType.LARGE));
        lot.addLevel(l0);

        ParkingLevel l1 = new ParkingLevel(1);
        for (int i = 0; i < 3; i++) l1.addSpot(new ParkingSpot("L1-C-" + i, SpotType.COMPACT));
        for (int i = 0; i < 2; i++) l1.addSpot(new ParkingSpot("L1-L-" + i, SpotType.LARGE));
        lot.addLevel(l1);

        // concurrent parking
        ExecutorService ex = Executors.newFixedThreadPool(8);
        List<Future<Ticket>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final int idx = i;
            futures.add(ex.submit(() -> {
                Vehicle v = (idx % 3 == 0) ? new Bike("B-" + idx)
                        : (idx % 3 == 1) ? new Car("C-" + idx) : new Truck("T-" + idx);
                return lot.park(v);
            }));
        }

        List<Ticket> tickets = new ArrayList<>();
        for (Future<Ticket> f : futures) {
            try { Ticket t = f.get(); if (t != null) tickets.add(t); } catch (Exception ignored) {}
        }

        System.out.println("Available after park: " + lot.totalAvailable());

        // unpark half
        for (int i = 0; i < tickets.size() / 2; i++) {
            Ticket t = tickets.get(i);
            lot.unpark(t.getId(), PaymentMethod.UPI);
        }

        System.out.println("Available after some exit: " + lot.totalAvailable());
        ex.shutdown();
    }
}
