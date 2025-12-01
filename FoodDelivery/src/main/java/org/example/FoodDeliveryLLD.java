package org.example;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.text.DecimalFormat;

/**
 * SDE 3 Food Delivery System LLD
 * * Features:
 * 1. Strategy Pattern for Driver Assignment (Extensible algorithms).
 * 2. Observer Pattern for Real-time Order Tracking.
 * 3. Thread Safety using ConcurrentHashMaps and AtomicIntegers.
 * 4. Synchronization blocks to handle Race Conditions on driver availability.
 */

// =======================
// 1. Enums & Helpers
// =======================

enum OrderStatus {
    PLACED, ACCEPTED, COOKING, READY_FOR_PICKUP, OUT_FOR_DELIVERY, DELIVERED, CANCELLED
}

class Location {
    double latitude;
    double longitude;

    public Location(double lat, double lon) {
        this.latitude = lat;
        this.longitude = lon;
    }

    // Euclidean distance (Simplified for interview)
    // SDE 3 Note: In production, use Haversine formula or S2 Geometry Lib
    public double distanceTo(Location other) {
        return Math.sqrt(Math.pow(this.latitude - other.latitude, 2) +
                Math.pow(this.longitude - other.longitude, 2));
    }
}

// =======================
// 2. Core Entities
// =======================

abstract class User {
    protected int id;
    protected String name;
    protected Location location;

    public User(int id, String name, Location location) {
        this.id = id;
        this.name = name;
        this.location = location;
    }

    public String getName() { return name; }
    public Location getLocation() { return location; }
}

class Customer extends User {
    public Customer(int id, String name, Location location) {
        super(id, name, location);
    }
}

class DeliveryPartner extends User {
    private volatile boolean isAvailable; // volatile for thread visibility

    public DeliveryPartner(int id, String name, Location location) {
        super(id, name, location);
        this.isAvailable = true;
    }

    public boolean isAvailable() { return isAvailable; }
    public void setAvailable(boolean available) { this.isAvailable = available; }
}

class MenuItem {
    String name;
    double price;

    public MenuItem(String name, double price) {
        this.name = name;
        this.price = price;
    }
}

class Restaurant {
    int id;
    String name;
    Location location;
    List<MenuItem> menu;

    public Restaurant(int id, String name, Location location) {
        this.id = id;
        this.name = name;
        this.location = location;
        this.menu = new CopyOnWriteArrayList<>(); // Thread-safe list
    }

    public void addMenuItem(MenuItem item) {
        menu.add(item);
    }
}

// =======================
// 3. Observer Pattern (Tracking)
// =======================

interface OrderObserver {
    void update(Order order);
}

class NotificationService implements OrderObserver {
    @Override
    public void update(Order order) {
        System.out.println("[NOTIFICATION SERVICE] Order #" + order.getId() + " status changed to: " + order.getStatus());
        if (order.getStatus() == OrderStatus.DELIVERED) {
            System.out.println("   >> Email sent to " + order.getCustomer().getName() + ": Enjoy your food!");
        }
    }
}

class Order {
    private int id;
    private Customer customer;
    private Restaurant restaurant;
    private DeliveryPartner driver;
    private List<MenuItem> items;
    private double totalAmount;
    private OrderStatus status;

    // Observers list
    private List<OrderObserver> observers = new ArrayList<>();

    public Order(int id, Customer customer, Restaurant restaurant, List<MenuItem> items) {
        this.id = id;
        this.customer = customer;
        this.restaurant = restaurant;
        this.items = items;
        this.status = OrderStatus.PLACED;
        this.totalAmount = items.stream().mapToDouble(i -> i.price).sum();
    }

    public void addObserver(OrderObserver observer) {
        observers.add(observer);
    }

    public void setStatus(OrderStatus newStatus) {
        this.status = newStatus;
        notifyObservers();
    }

    public void setDriver(DeliveryPartner driver) {
        this.driver = driver;
    }

    private void notifyObservers() {
        for (OrderObserver observer : observers) {
            observer.update(this);
        }
    }

    // Getters
    public int getId() { return id; }
    public OrderStatus getStatus() { return status; }
    public Customer getCustomer() { return customer; }
    public Restaurant getRestaurant() { return restaurant; }
    public DeliveryPartner getDriver() { return driver; }
}

// =======================
// 4. Strategy Pattern (Assignment)
// =======================

interface DeliveryAssignmentStrategy {
    DeliveryPartner assignDriver(Order order, List<DeliveryPartner> availableDrivers);
}

class ProximityAssignmentStrategy implements DeliveryAssignmentStrategy {
    @Override
    public DeliveryPartner assignDriver(Order order, List<DeliveryPartner> availableDrivers) {
        // SDE 3: Simple O(N) approach. Discuss Spatial Indexing (QuadTree) for optimization.
        DeliveryPartner bestDriver = null;
        double minDistance = Double.MAX_VALUE;

        for (DeliveryPartner driver : availableDrivers) {
            double distance = driver.getLocation().distanceTo(order.getRestaurant().location);
            if (distance < minDistance) {
                minDistance = distance;
                bestDriver = driver;
            }
        }
        return bestDriver;
    }
}

// =======================
// 5. System Facade / Manager
// =======================

class FoodDeliverySystem {
    private Map<Integer, Customer> customers = new ConcurrentHashMap<>();
    private Map<Integer, Restaurant> restaurants = new ConcurrentHashMap<>();
    private Map<Integer, DeliveryPartner> drivers = new ConcurrentHashMap<>();
    private Map<Integer, Order> orders = new ConcurrentHashMap<>();

    private DeliveryAssignmentStrategy assignmentStrategy;
    private AtomicInteger orderIdGenerator = new AtomicInteger(1);

    public FoodDeliverySystem(DeliveryAssignmentStrategy strategy) {
        this.assignmentStrategy = strategy;
    }

    // --- Registration Methods ---
    public void registerCustomer(Customer c) { customers.put(c.id, c); }
    public void registerRestaurant(Restaurant r) { restaurants.put(r.id, r); }
    public void registerDriver(DeliveryPartner d) { drivers.put(d.id, d); }

    // --- Core Business Logic ---

    public Order placeOrder(int customerId, int restaurantId, List<MenuItem> items) {
        Customer customer = customers.get(customerId);
        Restaurant restaurant = restaurants.get(restaurantId);

        if (customer == null || restaurant == null) {
            throw new IllegalArgumentException("Invalid Customer or Restaurant ID");
        }

        Order order = new Order(orderIdGenerator.getAndIncrement(), customer, restaurant, items);

        // Attach Observers (Push Notifications, Analytics, etc.)
        order.addObserver(new NotificationService());

        orders.put(order.getId(), order);
        System.out.println("LOG: Order Placed by " + customer.getName());

        // Trigger Assignment
        assignDriverToOrder(order);

        return order;
    }

    private void assignDriverToOrder(Order order) {
        // 1. Get snapshot of available drivers
        List<DeliveryPartner> availableDrivers = new ArrayList<>();
        for (DeliveryPartner d : drivers.values()) {
            if (d.isAvailable()) {
                availableDrivers.add(d);
            }
        }

        // 2. Use Strategy to pick the best one
        DeliveryPartner selectedDriver = assignmentStrategy.assignDriver(order, availableDrivers);

        if (selectedDriver != null) {
            // SDE 3 CRITICAL: Handle Race Condition.
            // Two orders might try to grab the same driver simultaneously.
            synchronized (selectedDriver) {
                if (selectedDriver.isAvailable()) {
                    selectedDriver.setAvailable(false); // Lock the driver
                    order.setDriver(selectedDriver);
                    order.setStatus(OrderStatus.ACCEPTED);
                    System.out.println("LOG: Assigned Driver [" + selectedDriver.getName() + "] to Order #" + order.getId());
                } else {
                    // Driver was taken by another thread between selection and locking. Retry.
                    System.out.println("LOG: Race condition detected. Retrying assignment...");
                    assignDriverToOrder(order); // Recursive retry
                }
            }
        } else {
            System.out.println("LOG: No drivers available. Order #" + order.getId() + " is pending.");
        }
    }
}

// =======================
// 6. Main Execution (Test)
// =======================

public class FoodDeliveryLLD {
    public static void main(String[] args) {
        System.out.println("--- Starting SDE 3 Food Delivery System Demo ---\n");

        // 1. Initialize System with Proximity Strategy
        FoodDeliverySystem system = new FoodDeliverySystem(new ProximityAssignmentStrategy());

        // 2. Setup Data
        Location locCustomer = new Location(0, 0);
        Location locRestaurant = new Location(10, 10);

        Customer customer = new Customer(1, "Alice", locCustomer);
        Restaurant restaurant = new Restaurant(1, "Burger King", locRestaurant);
        restaurant.addMenuItem(new MenuItem("Whopper", 5.99));

        system.registerCustomer(customer);
        system.registerRestaurant(restaurant);

        // 3. Add Drivers (One near, one far)
        Location locDriverNear = new Location(11, 11); // Distance ~1.41
        Location locDriverFar = new Location(50, 50);  // Distance ~56.5

        DeliveryPartner d1 = new DeliveryPartner(1, "Bob (Near)", locDriverNear);
        DeliveryPartner d2 = new DeliveryPartner(2, "Charlie (Far)", locDriverFar);

        system.registerDriver(d1);
        system.registerDriver(d2);

        // 4. Place Order
        List<MenuItem> items = restaurant.menu; // Ordering everything on menu
        Order order = system.placeOrder(1, 1, items);

        // 5. Simulate Delivery Lifecycle
        if (order.getDriver() != null) {
            simulateDeliveryProcess(order);
        }
    }

    // Helper to simulate time passing
    private static void simulateDeliveryProcess(Order order) {
        try {
            Thread.sleep(1000);
            order.setStatus(OrderStatus.COOKING);

            Thread.sleep(1000);
            order.setStatus(OrderStatus.READY_FOR_PICKUP);

            Thread.sleep(1000);
            order.setStatus(OrderStatus.OUT_FOR_DELIVERY);

            Thread.sleep(1000);
            order.setStatus(OrderStatus.DELIVERED);

            // Important: Release the driver back to the pool
            order.getDriver().setAvailable(true);
            System.out.println("LOG: Driver " + order.getDriver().getName() + " is now available again.");

        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}