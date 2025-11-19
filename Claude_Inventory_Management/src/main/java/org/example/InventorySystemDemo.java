package org.example;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;
import java.time.LocalDateTime;

// Product class representing individual products
class Product {
    private final String productId;
    private final String name;
    private final String category;
    private final double price;
    private final String description;

    public Product(String productId, String name, String category, double price, String description) {
        this.productId = productId;
        this.name = name;
        this.category = category;
        this.price = price;
        this.description = description;
    }

    public String getProductId() { return productId; }
    public String getName() { return name; }
    public String getCategory() { return category; }
    public double getPrice() { return price; }
    public String getDescription() { return description; }

    @Override
    public String toString() {
        return String.format("Product[ID=%s, Name=%s, Category=%s, Price=%.2f]",
                productId, name, category, price);
    }
}

// Inventory Item representing stock of a product in a warehouse
class InventoryItem {
    private final Product product;
    private int quantity;
    private final int reorderLevel;
    private final int maxCapacity;
    private LocalDateTime lastUpdated;

    public InventoryItem(Product product, int quantity, int reorderLevel, int maxCapacity) {
        this.product = product;
        this.quantity = quantity;
        this.reorderLevel = reorderLevel;
        this.maxCapacity = maxCapacity;
        this.lastUpdated = LocalDateTime.now();
    }

    public Product getProduct() { return product; }
    public int getQuantity() { return quantity; }
    public int getReorderLevel() { return reorderLevel; }
    public int getMaxCapacity() { return maxCapacity; }
    public LocalDateTime getLastUpdated() { return lastUpdated; }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
        this.lastUpdated = LocalDateTime.now();
    }

    public boolean isLowStock() {
        return quantity <= reorderLevel;
    }

    @Override
    public String toString() {
        return String.format("InventoryItem[Product=%s, Quantity=%d, ReorderLevel=%d, Status=%s]",
                product.getName(), quantity, reorderLevel, isLowStock() ? "LOW STOCK" : "OK");
    }
}

// Warehouse class managing inventory
class Warehouse {
    private final String warehouseId;
    private final String name;
    private final String location;
    private final ConcurrentHashMap<String, InventoryItem> inventory;
    private final ReentrantReadWriteLock lock;
    private final List<InventoryObserver> observers;

    public Warehouse(String warehouseId, String name, String location) {
        this.warehouseId = warehouseId;
        this.name = name;
        this.location = location;
        this.inventory = new ConcurrentHashMap<>();
        this.lock = new ReentrantReadWriteLock();
        this.observers = new CopyOnWriteArrayList<>();
    }

    public String getWarehouseId() { return warehouseId; }
    public String getName() { return name; }
    public String getLocation() { return location; }

    public void addObserver(InventoryObserver observer) {
        observers.add(observer);
    }

    public boolean addStock(Product product, int quantity, int reorderLevel, int maxCapacity) {
        lock.writeLock().lock();
        try {
            String productId = product.getProductId();
            InventoryItem item = inventory.get(productId);

            if (item == null) {
                item = new InventoryItem(product, quantity, reorderLevel, maxCapacity);
                inventory.put(productId, item);
                System.out.println(String.format("[%s] Added new product: %s with quantity: %d",
                        name, product.getName(), quantity));
            } else {
                int newQuantity = item.getQuantity() + quantity;
                if (newQuantity > item.getMaxCapacity()) {
                    System.out.println(String.format("[%s] Cannot add stock. Would exceed max capacity: %d",
                            name, item.getMaxCapacity()));
                    return false;
                }
                item.setQuantity(newQuantity);
                System.out.println(String.format("[%s] Updated %s quantity to: %d",
                        name, product.getName(), newQuantity));
            }
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean removeStock(String productId, int quantity) {
        lock.writeLock().lock();
        try {
            InventoryItem item = inventory.get(productId);

            if (item == null) {
                System.out.printf("[%s] Product not found: %s%n", name, productId);
                return false;
            }

            if (item.getQuantity() < quantity) {
                System.out.printf("[%s] Insufficient stock for %s. Available: %d, Requested: %d%n",
                        name, item.getProduct().getName(), item.getQuantity(), quantity);
                return false;
            }

            int newQuantity = item.getQuantity() - quantity;
            item.setQuantity(newQuantity);
            System.out.printf("[%s] Removed %d units of %s. New quantity: %d%n",
                    name, quantity, item.getProduct().getName(), newQuantity);

            // Check if low stock and notify observers
            if (item.isLowStock()) {
                notifyLowStock(item);
            }

            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public InventoryItem getInventoryItem(String productId) {
        lock.readLock().lock();
        try {
            return inventory.get(productId);
        } finally {
            lock.readLock().unlock();
        }
    }

    public Map<String, InventoryItem> getAllInventory() {
        lock.readLock().lock();
        try {
            return new HashMap<>(inventory);
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<InventoryItem> getLowStockItems() {
        lock.readLock().lock();
        try {
            List<InventoryItem> lowStockItems = new ArrayList<>();
            for (InventoryItem item : inventory.values()) {
                if (item.isLowStock()) {
                    lowStockItems.add(item);
                }
            }
            return lowStockItems;
        } finally {
            lock.readLock().unlock();
        }
    }

    private void notifyLowStock(InventoryItem item) {
        for (InventoryObserver observer : observers) {
            observer.onLowStock(this, item);
        }
    }

    @Override
    public String toString() {
        return String.format("Warehouse[ID=%s, Name=%s, Location=%s, Products=%d]",
                warehouseId, name, location, inventory.size());
    }
}

// Observer interface for inventory notifications
interface InventoryObserver {
    void onLowStock(Warehouse warehouse, InventoryItem item);
}

// Low Stock Alert System
class LowStockAlertSystem implements InventoryObserver {
    @Override
    public void onLowStock(Warehouse warehouse, InventoryItem item) {
        System.out.println(String.format("\n*** LOW STOCK ALERT ***"));
        System.out.println(String.format("Warehouse: %s", warehouse.getName()));
        System.out.println(String.format("Product: %s", item.getProduct().getName()));
        System.out.println(String.format("Current Quantity: %d", item.getQuantity()));
        System.out.println(String.format("Reorder Level: %d", item.getReorderLevel()));
        System.out.println(String.format("Action Required: Reorder immediately!\n"));
    }
}

// Main Inventory Management System
class InventoryManagementSystem {
    private final ConcurrentHashMap<String, Warehouse> warehouses;
    private final ConcurrentHashMap<String, Product> products;
    private final ReadWriteLock lock;

    public InventoryManagementSystem() {
        this.warehouses = new ConcurrentHashMap<>();
        this.products = new ConcurrentHashMap<>();
        this.lock = new ReentrantReadWriteLock();
    }

    public void addWarehouse(Warehouse warehouse) {
        warehouses.put(warehouse.getWarehouseId(), warehouse);
        System.out.println(String.format("Added warehouse: %s", warehouse));
    }

    public void addProduct(Product product) {
        products.put(product.getProductId(), product);
        System.out.println(String.format("Added product to catalog: %s", product));
    }

    public boolean transferStock(String fromWarehouseId, String toWarehouseId,
                                 String productId, int quantity) {
        lock.writeLock().lock();
        try {
            Warehouse fromWarehouse = warehouses.get(fromWarehouseId);
            Warehouse toWarehouse = warehouses.get(toWarehouseId);

            if (fromWarehouse == null || toWarehouse == null) {
                System.out.println("Invalid warehouse IDs for transfer");
                return false;
            }

            InventoryItem fromItem = fromWarehouse.getInventoryItem(productId);
            if (fromItem == null) {
                System.out.println("Product not found in source warehouse");
                return false;
            }

            if (fromWarehouse.removeStock(productId, quantity)) {
                Product product = fromItem.getProduct();
                toWarehouse.addStock(product, quantity, fromItem.getReorderLevel(),
                        fromItem.getMaxCapacity());
                System.out.println(String.format("Transferred %d units of %s from %s to %s",
                        quantity, product.getName(), fromWarehouse.getName(), toWarehouse.getName()));
                return true;
            }

            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void generateInventoryReport() {
        System.out.println("\n========== INVENTORY REPORT ==========");
        System.out.println("Generated at: " + LocalDateTime.now());

        for (Warehouse warehouse : warehouses.values()) {
            System.out.println("\n" + warehouse);
            System.out.println("-----------------------------------");

            Map<String, InventoryItem> inventory = warehouse.getAllInventory();
            if (inventory.isEmpty()) {
                System.out.println("  No inventory items");
            } else {
                for (InventoryItem item : inventory.values()) {
                    System.out.println("  " + item);
                }
            }

            List<InventoryItem> lowStock = warehouse.getLowStockItems();
            if (!lowStock.isEmpty()) {
                System.out.println("\n  LOW STOCK ITEMS:");
                for (InventoryItem item : lowStock) {
                    System.out.println("    - " + item.getProduct().getName() +
                            " (Qty: " + item.getQuantity() + ")");
                }
            }
        }
        System.out.println("\n=====================================\n");
    }

    public Warehouse getWarehouse(String warehouseId) {
        return warehouses.get(warehouseId);
    }

    public Product getProduct(String productId) {
        return products.get(productId);
    }
}

// Demo class to test the system
public class InventorySystemDemo {
    public static void main(String[] args) throws InterruptedException {
        // Create the inventory management system
        InventoryManagementSystem ims = new InventoryManagementSystem();

        // Create warehouses
        Warehouse warehouse1 = new Warehouse("WH001", "Main Warehouse", "New York");
        Warehouse warehouse2 = new Warehouse("WH002", "Secondary Warehouse", "Los Angeles");

        // Add low stock alert observer
        LowStockAlertSystem alertSystem = new LowStockAlertSystem();
        warehouse1.addObserver(alertSystem);
        warehouse2.addObserver(alertSystem);

        ims.addWarehouse(warehouse1);
        ims.addWarehouse(warehouse2);

        // Create products
        Product laptop = new Product("P001", "Laptop Pro 15", "Electronics", 1299.99, "High-performance laptop");
        Product mouse = new Product("P002", "Wireless Mouse", "Electronics", 29.99, "Ergonomic wireless mouse");
        Product keyboard = new Product("P003", "Mechanical Keyboard", "Electronics", 89.99, "RGB mechanical keyboard");

        ims.addProduct(laptop);
        ims.addProduct(mouse);
        ims.addProduct(keyboard);

        System.out.println("\n========== INITIAL STOCK SETUP ==========\n");

        // Add stock to warehouses
        warehouse1.addStock(laptop, 50, 10, 100);
        warehouse1.addStock(mouse, 200, 50, 500);
        warehouse1.addStock(keyboard, 100, 20, 200);

        warehouse2.addStock(laptop, 30, 10, 100);
        warehouse2.addStock(mouse, 100, 50, 500);

        // Generate initial report
        ims.generateInventoryReport();

        System.out.println("\n========== STOCK OPERATIONS ==========\n");

        // Simulate stock removal
        warehouse1.removeStock("P001", 20);
        warehouse1.removeStock("P002", 155); // This should trigger low stock alert

        // Transfer stock between warehouses
        ims.transferStock("WH001", "WH002", "P001", 15);

        // Test thread safety with concurrent operations
        System.out.println("\n========== CONCURRENT OPERATIONS TEST ==========\n");

        ExecutorService executor = Executors.newFixedThreadPool(5);
        CountDownLatch latch = new CountDownLatch(10);

        // Multiple threads trying to add/remove stock simultaneously
        for (int i = 0; i < 5; i++) {
            final int threadNum = i;
            executor.submit(() -> {
                try {
                    warehouse1.addStock(mouse, 10, 50, 500);
                    System.out.println("Thread " + threadNum + " added stock");
                } finally {
                    latch.countDown();
                }
            });

            executor.submit(() -> {
                try {
                    warehouse1.removeStock("P002", 5);
                    System.out.println("Thread " + threadNum + " removed stock");
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // Final report
        ims.generateInventoryReport();

        System.out.println("Demo completed successfully!");
    }
}