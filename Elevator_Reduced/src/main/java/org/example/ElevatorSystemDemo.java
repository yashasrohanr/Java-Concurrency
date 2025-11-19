package org.example;

import java.util.*;
import java.util.concurrent.locks.*;

// 1. Enums
enum State { IDLE, MOVING, MAINTENANCE }
enum Direction { UP, DOWN, NONE }

// 2. Strategy Pattern
interface DispatchStrategy {
    Elevator selectElevator(List<Elevator> elevators, int sourceFloor, Direction dir);
}

class NearestElevatorStrategy implements DispatchStrategy {
    @Override
    public Elevator selectElevator(List<Elevator> elevators, int sourceFloor, Direction dir) {
        return elevators.stream()
                .filter(e -> e.getState() != State.MAINTENANCE)
                .min(Comparator.comparingInt(e -> Math.abs(e.getCurrentFloor() - sourceFloor)))
                .orElseThrow(() -> new RuntimeException("No Elevators Available"));
    }
}

// 3. The Elevator (Worker)
class Elevator implements Runnable {
    private final int id;
    private int currentFloor = 0;
    private State state = State.IDLE;
    private Direction direction = Direction.NONE;
    private volatile boolean running = true; // FIX: Flag to stop thread

    // FIX: Must be ReentrantLock (not Lock interface) to use isHeldByCurrentThread()
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition hasRequest = lock.newCondition();

    private final PriorityQueue<Integer> upQueue = new PriorityQueue<>();
    private final PriorityQueue<Integer> downQueue = new PriorityQueue<>(Collections.reverseOrder());

    public Elevator(int id) { this.id = id; }

    public void stop() { running = false; } // FIX: Method to kill loop

    public void setMaintenance() {
        lock.lock();
        try {
            this.state = State.MAINTENANCE;
            this.upQueue.clear();
            this.downQueue.clear();
            printDisplay("OUT OF SERVICE");
        } finally { lock.unlock(); }
    }

    public void addRequest(int floor) {
        lock.lock();
        try {
            if (state == State.MAINTENANCE) return;
            if (floor > currentFloor) upQueue.add(floor);
            else if (floor < currentFloor) downQueue.add(floor);
            hasRequest.signalAll();
        } finally { lock.unlock(); }
    }

    @Override
    public void run() {
        while (running) { // FIX: check running flag
            lock.lock();
            try {
                while (running && upQueue.isEmpty() && downQueue.isEmpty()) {
                    state = State.IDLE;
                    direction = Direction.NONE;
                    // Wait for 1 second or until signaled, to check 'running' flag periodically
                    // If we wait forever, we might miss the shutdown signal
                    hasRequest.await(1, java.util.concurrent.TimeUnit.SECONDS);
                }
                if (!running) break;
                if (state != State.MAINTENANCE) processNextMove();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } finally { lock.unlock(); }
        }
        System.out.println("Elevator " + id + " Thread Stopped.");
    }

    private void processNextMove() throws InterruptedException {
        direction = Direction.NONE;
        if (!upQueue.isEmpty()) {
            move(upQueue, Direction.UP);
        }
        else if (!downQueue.isEmpty()) {
            move(downQueue, Direction.DOWN);
        }
    }

    private void move(PriorityQueue<Integer> queue, Direction dir) throws InterruptedException {
        this.direction = dir;
        this.state = State.MOVING;

        lock.unlock(); // Release lock so buttons still work while moving
        try {
            Thread.sleep(2000); // Simulate physical movement
        } finally {
            lock.lock(); // Re-acquire lock immediately
        }

        // Check queue again after re-acquiring lock
        if (!queue.isEmpty()) {
            currentFloor = queue.poll();
            printDisplay("Arrived Floor " + currentFloor);
        }
    }

    private void printDisplay(String msg) {
        System.out.printf("[Display E%d] %s | State: %s\n", id, msg, state);
    }

    public int getCurrentFloor() { return currentFloor; }
    public State getState() { return state; }
}

// 4. Button & Floor (Same as before)
class Button {
    private final Building building;
    private final int floor;
    public Button(Building b, int f) { this.building = b; this.floor = f; }
    public void press(Direction dir) {
        System.out.println(">> Button Pressed: Floor " + floor + " " + dir);
        building.dispatch(floor, dir);
    }
}

class Floor {
    public final Button upBtn;
    public final Button downBtn;
    public Floor(int f, Building b) {
        this.upBtn = new Button(b, f);
        this.downBtn = new Button(b, f);
    }
}

// 5. Building (Now with Shutdown)
class Building {
    private final List<Elevator> elevators = new ArrayList<>();
    private final List<Floor> floors = new ArrayList<>();
    private final DispatchStrategy strategy;

    public Building(int numFloors, int numElevators, DispatchStrategy strategy) {
        this.strategy = strategy;
        for (int i = 0; i < numElevators; i++) {
            Elevator e = new Elevator(i + 1);
            elevators.add(e);
            new Thread(e).start();
        }
        for (int i = 0; i < numFloors; i++) {
            floors.add(new Floor(i, this));
        }
    }

    public void dispatch(int floor, Direction dir) {
        Elevator e = strategy.selectElevator(elevators, floor, dir);
        e.addRequest(floor);
    }

    public void shutdown() {
        System.out.println("\n=== SHUTTING DOWN SYSTEM ===");
        for(Elevator e : elevators) e.stop();
    }

    public Floor getFloor(int f) { return floors.get(f); }
    public Elevator getElevator(int id) { return elevators.get(id - 1); }
}

// 6. Main
public class ElevatorSystemDemo {
    public static void main(String[] args) throws InterruptedException {
        Building building = new Building(10, 3, new NearestElevatorStrategy());

        // Scenario
        building.getFloor(5).upBtn.press(Direction.UP);
        Thread.sleep(500);

        building.getElevator(1).addRequest(9);
        Thread.sleep(2000);

        // Shutdown
        building.shutdown();
    }
}