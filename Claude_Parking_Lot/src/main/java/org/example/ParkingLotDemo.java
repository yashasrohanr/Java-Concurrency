package org.example;

// ==================== ENUMS ====================

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

enum VehicleType {
    MOTORCYCLE(1),
    CAR(2),
    BUS(4);

    private final int spotsRequired;

    VehicleType(int spotsRequired) {
        this.spotsRequired = spotsRequired;
    }

    public int getSpotsRequired() {
        return spotsRequired;
    }
}

enum SpotType {
    MOTORCYCLE,
    COMPACT,
    LARGE,
    HANDICAPPED,
    EV_CHARGING
}

enum SpotStatus {
    AVAILABLE,
    OCCUPIED,
    RESERVED,
    OUT_OF_SERVICE
}

enum PaymentStatus {
    PENDING,
    COMPLETED,
    FAILED,
    REFUNDED
}

// ==================== VEHICLE CLASSES ====================

abstract class Vehicle {
    protected String licensePlate;
    protected VehicleType type;

    public Vehicle(String licensePlate, VehicleType type) {
        this.licensePlate = licensePlate;
        this.type = type;
    }

    public String getLicensePlate() {
        return licensePlate;
    }

    public VehicleType getType() {
        return type;
    }

    public int getSpotsRequired() {
        return type.getSpotsRequired();
    }
}

class Motorcycle extends Vehicle {
    public Motorcycle(String licensePlate) {
        super(licensePlate, VehicleType.MOTORCYCLE);
    }
}

class Car extends Vehicle {
    public Car(String licensePlate) {
        super(licensePlate, VehicleType.CAR);
    }
}

class Bus extends Vehicle {
    public Bus(String licensePlate) {
        super(licensePlate, VehicleType.BUS);
    }
}

class ElectricVehicle extends Car {
    private int batteryLevel;

    public ElectricVehicle(String licensePlate, int batteryLevel) {
        super(licensePlate);
        this.batteryLevel = batteryLevel;
    }

    public int getBatteryLevel() {
        return batteryLevel;
    }

    public boolean needsCharging() {
        return batteryLevel < 50;
    }
}

// ==================== PARKING SPOT ====================

abstract class ParkingSpot {
    protected String spotId;
    protected SpotType type;
    protected SpotStatus status;
    protected Vehicle currentVehicle;
    protected int floor;

    public ParkingSpot(String spotId, SpotType type, int floor) {
        this.spotId = spotId;
        this.type = type;
        this.status = SpotStatus.AVAILABLE;
        this.floor = floor;
    }

    public synchronized boolean isAvailable() {
        return status == SpotStatus.AVAILABLE;
    }

    public synchronized boolean assignVehicle(Vehicle vehicle) {
        if (!canFitVehicle(vehicle)) {
            return false;
        }
        this.currentVehicle = vehicle;
        this.status = SpotStatus.OCCUPIED;
        return true;
    }

    public synchronized void removeVehicle() {
        this.currentVehicle = null;
        this.status = SpotStatus.AVAILABLE;
    }

    public synchronized void reserve() {
        if (status == SpotStatus.AVAILABLE) {
            status = SpotStatus.RESERVED;
        }
    }

    public abstract boolean canFitVehicle(Vehicle vehicle);

    // Getters
    public String getSpotId() { return spotId; }
    public SpotType getType() { return type; }
    public SpotStatus getStatus() { return status; }
    public Vehicle getCurrentVehicle() { return currentVehicle; }
    public int getFloor() { return floor; }
}

class MotorcycleSpot extends ParkingSpot {
    public MotorcycleSpot(String spotId, int floor) {
        super(spotId, SpotType.MOTORCYCLE, floor);
    }

    @Override
    public boolean canFitVehicle(Vehicle vehicle) {
        return vehicle.getType() == VehicleType.MOTORCYCLE;
    }
}

class CompactSpot extends ParkingSpot {
    public CompactSpot(String spotId, int floor) {
        super(spotId, SpotType.COMPACT, floor);
    }

    @Override
    public boolean canFitVehicle(Vehicle vehicle) {
        return vehicle.getType() == VehicleType.MOTORCYCLE ||
                vehicle.getType() == VehicleType.CAR;
    }
}

class LargeSpot extends ParkingSpot {
    public LargeSpot(String spotId, int floor) {
        super(spotId, SpotType.LARGE, floor);
    }

    @Override
    public boolean canFitVehicle(Vehicle vehicle) {
        return true; // Can fit any vehicle
    }
}

class HandicappedSpot extends ParkingSpot {
    public HandicappedSpot(String spotId, int floor) {
        super(spotId, SpotType.HANDICAPPED, floor);
    }

    @Override
    public boolean canFitVehicle(Vehicle vehicle) {
        return vehicle.getType() == VehicleType.CAR;
    }
}

class EVChargingSpot extends ParkingSpot {
    private boolean isCharging;

    public EVChargingSpot(String spotId, int floor) {
        super(spotId, SpotType.EV_CHARGING, floor);
        this.isCharging = false;
    }

    @Override
    public boolean canFitVehicle(Vehicle vehicle) {
        return vehicle instanceof ElectricVehicle;
    }

    public void startCharging() {
        this.isCharging = true;
    }

    public void stopCharging() {
        this.isCharging = false;
    }
}

// ==================== TICKET ====================

class Ticket {
    private final String ticketId;
    private final Vehicle vehicle;
    private final List<ParkingSpot> assignedSpots;
    private final long entryTime;
    private long exitTime;
    private double amount;
    private PaymentStatus paymentStatus;

    public Ticket(String ticketId, Vehicle vehicle, List<ParkingSpot> spots) {
        this.ticketId = ticketId;
        this.vehicle = vehicle;
        this.assignedSpots = new ArrayList<>(spots);
        this.entryTime = System.currentTimeMillis();
        this.paymentStatus = PaymentStatus.PENDING;
    }

    public void markExit(long exitTime, double amount) {
        this.exitTime = exitTime;
        this.amount = amount;
    }

    public void markPaid() {
        this.paymentStatus = PaymentStatus.COMPLETED;
    }

    public long getDurationInMinutes() {
        long endTime = exitTime > 0 ? exitTime : System.currentTimeMillis();
        return (endTime - entryTime) / (1000 * 60);
    }

    // Getters
    public String getTicketId() { return ticketId; }
    public Vehicle getVehicle() { return vehicle; }
    public List<ParkingSpot> getAssignedSpots() { return assignedSpots; }
    public long getEntryTime() { return entryTime; }
    public long getExitTime() { return exitTime; }
    public double getAmount() { return amount; }
    public PaymentStatus getPaymentStatus() { return paymentStatus; }
}

// ==================== PARKING STRATEGIES ====================

interface ParkingStrategy {
    List<ParkingSpot> findSpots(List<ParkingLevel> levels, Vehicle vehicle);
}

class NearestFirstStrategy implements ParkingStrategy {
    @Override
    public List<ParkingSpot> findSpots(List<ParkingLevel> levels, Vehicle vehicle) {
        int spotsNeeded = vehicle.getSpotsRequired();

        // Try each level sequentially
        for (ParkingLevel level : levels) {
            List<ParkingSpot> spots = level.findAvailableSpots(vehicle, spotsNeeded);
            if (spots.size() == spotsNeeded) {
                return spots;
            }
        }
        return Collections.emptyList();
    }
}

class PreferredTypeStrategy implements ParkingStrategy {
    @Override
    public List<ParkingSpot> findSpots(List<ParkingLevel> levels, Vehicle vehicle) {
        int spotsNeeded = vehicle.getSpotsRequired();

        // For electric vehicles, prefer EV charging spots
        if (vehicle instanceof ElectricVehicle) {
            for (ParkingLevel level : levels) {
                List<ParkingSpot> evSpots = level.findEVSpots(spotsNeeded);
                if (!evSpots.isEmpty()) {
                    return evSpots;
                }
            }
        }

        // Fall back to nearest first
        return new NearestFirstStrategy().findSpots(levels, vehicle);
    }
}

// ==================== PRICING STRATEGY ====================

interface PricingStrategy {
    double calculateFee(Ticket ticket);
}

class HourlyPricingStrategy implements PricingStrategy {
    private static final Map<VehicleType, Double> HOURLY_RATES = new HashMap<>();

    static {
        HOURLY_RATES.put(VehicleType.MOTORCYCLE, 5.0);
        HOURLY_RATES.put(VehicleType.CAR, 10.0);
        HOURLY_RATES.put(VehicleType.BUS, 20.0);
    }

    @Override
    public double calculateFee(Ticket ticket) {
        long durationMinutes = ticket.getDurationInMinutes();
        double hours = Math.ceil(durationMinutes / 60.0);
        double hourlyRate = HOURLY_RATES.getOrDefault(ticket.getVehicle().getType(), 10.0);
        return hours * hourlyRate;
    }
}

// ==================== PAYMENT SERVICE ====================

class PaymentService {
    public boolean processPayment(Ticket ticket, double amount) {
        // Simulate payment processing
        try {
            Thread.sleep(100); // Simulate payment gateway delay
            if (amount >= ticket.getAmount()) {
                ticket.markPaid();
                return true;
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}

// ==================== PARKING LEVEL ====================

class ParkingLevel {
    private final int levelNumber;
    private final List<ParkingSpot> spots;
    private final Map<SpotType, List<ParkingSpot>> spotsByType;
    private final AtomicInteger availableSpots;

    public ParkingLevel(int levelNumber) {
        this.levelNumber = levelNumber;
        this.spots = new CopyOnWriteArrayList<>();
        this.spotsByType = new ConcurrentHashMap<>();
        this.availableSpots = new AtomicInteger(0);

        // Initialize spot type map
        for (SpotType type : SpotType.values()) {
            spotsByType.put(type, new CopyOnWriteArrayList<>());
        }
    }

    public void addSpot(ParkingSpot spot) {
        spots.add(spot);
        spotsByType.get(spot.getType()).add(spot);
        availableSpots.incrementAndGet();
    }

    public synchronized List<ParkingSpot> findAvailableSpots(Vehicle vehicle, int count) {
        List<ParkingSpot> availableSpots = new ArrayList<>();

        // First try to find exact type match
        for (ParkingSpot spot : spots) {
            if (spot.isAvailable() && spot.canFitVehicle(vehicle)) {
                availableSpots.add(spot);
                if (availableSpots.size() == count) {
                    return availableSpots;
                }
            }
        }

        // If we found some but not enough, return empty
        return availableSpots.size() == count ? availableSpots : Collections.emptyList();
    }

    public synchronized List<ParkingSpot> findEVSpots(int count) {
        List<ParkingSpot> evSpots = new ArrayList<>();
        List<ParkingSpot> evChargingSpots = spotsByType.get(SpotType.EV_CHARGING);

        for (ParkingSpot spot : evChargingSpots) {
            if (spot.isAvailable()) {
                evSpots.add(spot);
                if (evSpots.size() == count) {
                    return evSpots;
                }
            }
        }
        return evSpots.size() == count ? evSpots : Collections.emptyList();
    }

    public int getAvailableSpotCount() {
        return (int) spots.stream().filter(ParkingSpot::isAvailable).count();
    }

    public int getLevelNumber() {
        return levelNumber;
    }
}

// ==================== PARKING LOT (SINGLETON) ====================

class ParkingLot {
    private static ParkingLot instance;
    private final List<ParkingLevel> levels;
    private final Map<String, Ticket> activeTickets;
    private final ParkingStrategy parkingStrategy;
    private final PricingStrategy pricingStrategy;
    private final PaymentService paymentService;
    private final AtomicInteger ticketCounter;

    private ParkingLot() {
        this.levels = new CopyOnWriteArrayList<>();
        this.activeTickets = new ConcurrentHashMap<>();
        this.parkingStrategy = new PreferredTypeStrategy();
        this.pricingStrategy = new HourlyPricingStrategy();
        this.paymentService = new PaymentService();
        this.ticketCounter = new AtomicInteger(0);
    }

    public static synchronized ParkingLot getInstance() {
        if (instance == null) {
            instance = new ParkingLot();
        }
        return instance;
    }

    public void addLevel(ParkingLevel level) {
        levels.add(level);
    }

    public synchronized Ticket parkVehicle(Vehicle vehicle) {
        // Find available spots
        List<ParkingSpot> spots = parkingStrategy.findSpots(levels, vehicle);

        if (spots.isEmpty()) {
            System.out.println("No available spots for vehicle: " + vehicle.getLicensePlate());
            return null;
        }

        // Assign vehicle to spots
        for (ParkingSpot spot : spots) {
            if (!spot.assignVehicle(vehicle)) {
                // Rollback if assignment fails
                spots.forEach(ParkingSpot::removeVehicle);
                return null;
            }
        }

        // Generate ticket
        String ticketId = "TICKET-" + ticketCounter.incrementAndGet();
        Ticket ticket = new Ticket(ticketId, vehicle, spots);
        activeTickets.put(ticketId, ticket);

        System.out.println("Vehicle " + vehicle.getLicensePlate() + " parked. Ticket: " + ticketId);
        return ticket;
    }

    public synchronized boolean unparkVehicle(String ticketId) {
        Ticket ticket = activeTickets.get(ticketId);

        if (ticket == null) {
            System.out.println("Invalid ticket ID: " + ticketId);
            return false;
        }

        // Calculate fee
        long exitTime = System.currentTimeMillis();
        double fee = pricingStrategy.calculateFee(ticket);
        ticket.markExit(exitTime, fee);

        System.out.println("Fee for ticket " + ticketId + ": $" + fee);
        System.out.println("Duration: " + ticket.getDurationInMinutes() + " minutes");

        // Process payment
        if (paymentService.processPayment(ticket, fee)) {
            // Release spots
            for (ParkingSpot spot : ticket.getAssignedSpots()) {
                spot.removeVehicle();
            }

            activeTickets.remove(ticketId);
            System.out.println("Vehicle " + ticket.getVehicle().getLicensePlate() + " exited successfully");
            return true;
        }

        System.out.println("Payment failed for ticket: " + ticketId);
        return false;
    }

    public void displayAvailability() {
        System.out.println("\n=== Parking Lot Status ===");
        for (ParkingLevel level : levels) {
            System.out.println("Level " + level.getLevelNumber() +
                    ": " + level.getAvailableSpotCount() + " spots available");
        }
        System.out.println("Active tickets: " + activeTickets.size());
    }

    public int getTotalAvailableSpots() {
        return levels.stream()
                .mapToInt(ParkingLevel::getAvailableSpotCount)
                .sum();
    }
}

// ==================== DEMO/TEST ====================

public class ParkingLotDemo {
    public static void main(String[] args) throws InterruptedException {
        // Initialize parking lot
        ParkingLot parkingLot = ParkingLot.getInstance();

        // Create 3 levels with different spot configurations
        for (int i = 0; i < 3; i++) {
            ParkingLevel level = new ParkingLevel(i);

            // Add motorcycle spots
            for (int j = 0; j < 10; j++) {
                level.addSpot(new MotorcycleSpot("L" + i + "-M" + j, i));
            }

            // Add compact spots
            for (int j = 0; j < 20; j++) {
                level.addSpot(new CompactSpot("L" + i + "-C" + j, i));
            }

            // Add large spots
            for (int j = 0; j < 15; j++) {
                level.addSpot(new LargeSpot("L" + i + "-L" + j, i));
            }

            // Add EV charging spots
            for (int j = 0; j < 5; j++) {
                level.addSpot(new EVChargingSpot("L" + i + "-EV" + j, i));
            }

            parkingLot.addLevel(level);
        }

        parkingLot.displayAvailability();

        // Test vehicle parking
        Vehicle car1 = new Car("ABC-123");
        Vehicle motorcycle1 = new Motorcycle("XYZ-789");
        Vehicle bus1 = new Bus("BUS-456");
        Vehicle ev1 = new ElectricVehicle("EV-001", 30);

        Ticket ticket1 = parkingLot.parkVehicle(car1);
        Ticket ticket2 = parkingLot.parkVehicle(motorcycle1);
        Ticket ticket3 = parkingLot.parkVehicle(bus1);
        Ticket ticket4 = parkingLot.parkVehicle(ev1);

        parkingLot.displayAvailability();

        // Simulate some time passing
        Thread.sleep(2000);

        // Unpark vehicles
        if (ticket1 != null) {
            parkingLot.unparkVehicle(ticket1.getTicketId());
        }

        if (ticket2 != null) {
            parkingLot.unparkVehicle(ticket2.getTicketId());
        }

        parkingLot.displayAvailability();

        // Test concurrent parking
        System.out.println("\n=== Testing Concurrent Operations ===");
        testConcurrentParking(parkingLot);
    }

    private static void testConcurrentParking(ParkingLot parkingLot) throws InterruptedException {
        int threadCount = 10;
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            new Thread(() -> {
                Vehicle vehicle = new Car("CONCURRENT-" + index);
                Ticket ticket = parkingLot.parkVehicle(vehicle);
                if (ticket != null) {
                    System.out.println("Thread " + index + " parked successfully");
                }
                latch.countDown();
            }).start();
        }

        latch.await();
        parkingLot.displayAvailability();
    }
}