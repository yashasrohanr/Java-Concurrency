package org.example;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

// Enumerations
enum ElevatorState {
    IDLE, MOVING_UP, MOVING_DOWN, STOPPED, DOOR_OPEN, DOOR_CLOSE, MAINTENANCE, EMERGENCY
}

enum Direction {
    UP, DOWN, NONE
}

enum DoorState {
    OPEN, CLOSED, OPENING, CLOSING
}

enum RequestStatus {
    PENDING, ASSIGNED, IN_PROGRESS, COMPLETED, CANCELLED
}

// Abstract Request Class
abstract class Request {
    private final String requestId;
    private final int sourceFloor;
    private final LocalDateTime timestamp;
    private volatile RequestStatus status;

    public Request(int floor) {
        this.requestId = UUID.randomUUID().toString();
        this.sourceFloor = floor;
        this.timestamp = LocalDateTime.now();
        this.status = RequestStatus.PENDING;
    }

    public int getSourceFloor() { return sourceFloor; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public RequestStatus getStatus() { return status; }
    public void setStatus(RequestStatus status) { this.status = status; }
    public String getRequestId() { return requestId; }
}

// Internal Request (from inside elevator)
class InternalRequest extends Request {
    private final int destinationFloor;
    private final int passengerId;

    public InternalRequest(int destination, int passengerId) {
        super(destination);
        this.destinationFloor = destination;
        this.passengerId = passengerId;
    }

    public int getDestinationFloor() { return destinationFloor; }
}

// External Request (from floor buttons)
class ExternalRequest extends Request {
    private final Direction direction;
    private final int waitingPassengers;

    public ExternalRequest(int floor, Direction direction) {
        super(floor);
        this.direction = direction;
        this.waitingPassengers = 1;
    }

    public Direction getDirection() { return direction; }
    public int getPriority() {
        return (int) Duration.between(getTimestamp(), LocalDateTime.now()).getSeconds();
    }
}

// Button Classes
abstract class Button {
    protected final int floorNumber;
    private final AtomicBoolean pressed;
    private volatile LocalDateTime pressedTime;

    public Button(int floor) {
        this.floorNumber = floor;
        this.pressed = new AtomicBoolean(false);
    }

    public void press() {
        if (pressed.compareAndSet(false, true)) {
            pressedTime = LocalDateTime.now();
            onPress();
        }
    }

    public void reset() {
        pressed.set(false);
    }

    public boolean isPressed() {
        return pressed.get();
    }

    protected abstract void onPress();
}

class UpButton extends Button {
    private final Floor floor;

    public UpButton(int floorNum, Floor floor) {
        super(floorNum);
        this.floor = floor;
    }

    @Override
    protected void onPress() {
        floor.notifyElevatorRequest(Direction.UP);
    }
}

class DownButton extends Button {
    private final Floor floor;

    public DownButton(int floorNum, Floor floor) {
        super(floorNum);
        this.floor = floor;
    }

    @Override
    protected void onPress() {
        floor.notifyElevatorRequest(Direction.DOWN);
    }
}

// Elevator Display Info
class ElevatorDisplayInfo {
    private final int elevatorId;
    private final int currentFloor;
    private final Direction direction;
    private final ElevatorState state;
    private final int estimatedArrivalTime;

    public ElevatorDisplayInfo(int id, int floor, Direction dir, ElevatorState state, int eta) {
        this.elevatorId = id;
        this.currentFloor = floor;
        this.direction = dir;
        this.state = state;
        this.estimatedArrivalTime = eta;
    }

    public String getDisplayString() {
        String arrow = direction == Direction.UP ? "↑" : direction == Direction.DOWN ? "↓" : "•";
        return String.format("E%d: Floor %d %s [%s]", elevatorId, currentFloor, arrow, state);
    }

    public int getElevatorId() { return elevatorId; }
    public int getCurrentFloor() { return currentFloor; }
}

// Display Board
class DisplayBoard {
    private final int floorNumber;
    private final Map<Integer, ElevatorDisplayInfo> elevatorInfo;
    private final ReentrantLock displayLock;

    public DisplayBoard(int floorNumber) {
        this.floorNumber = floorNumber;
        this.elevatorInfo = new ConcurrentHashMap<>();
        this.displayLock = new ReentrantLock();
    }

    public void updateElevatorInfo(int elevatorId, ElevatorDisplayInfo info) {
        displayLock.lock();
        try {
            elevatorInfo.put(elevatorId, info);
            refresh();
        } finally {
            displayLock.unlock();
        }
    }

    public void refresh() {
        StringBuilder display = new StringBuilder();
        display.append(String.format("\n=== Floor %d Display Board ===\n", floorNumber));

        elevatorInfo.values().stream()
                .sorted(Comparator.comparingInt(ElevatorDisplayInfo::getElevatorId))
                .forEach(info -> display.append(info.getDisplayString()).append("\n"));

        System.out.println(display.toString());
    }

    public String getDisplayContent() {
        displayLock.lock();
        try {
            return elevatorInfo.values().stream()
                    .sorted(Comparator.comparingInt(ElevatorDisplayInfo::getElevatorId))
                    .map(ElevatorDisplayInfo::getDisplayString)
                    .collect(Collectors.joining("\n"));
        } finally {
            displayLock.unlock();
        }
    }
}

// Door Controller
class DoorController {
    private final int elevatorId;
    private volatile DoorState doorState;
    private final ReentrantLock doorLock;
    private final int openDurationMs;
    private final ScheduledExecutorService doorTimer;

    public DoorController(int elevatorId) {
        this.elevatorId = elevatorId;
        this.doorState = DoorState.CLOSED;
        this.doorLock = new ReentrantLock();
        this.openDurationMs = 3000;
        this.doorTimer = Executors.newSingleThreadScheduledExecutor();
    }

    public void openDoors() {
        doorLock.lock();
        try {
            if (doorState == DoorState.CLOSED) {
                doorState = DoorState.OPENING;
                Thread.sleep(500); // Simulate opening time
                doorState = DoorState.OPEN;
                System.out.printf("[Elevator %d] Doors OPEN\n", elevatorId);
                scheduleAutoClose();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            doorLock.unlock();
        }
    }

    public void closeDoors() {
        doorLock.lock();
        try {
            if (doorState == DoorState.OPEN) {
                doorState = DoorState.CLOSING;
                Thread.sleep(500); // Simulate closing time
                doorState = DoorState.CLOSED;
                System.out.printf("[Elevator %d] Doors CLOSED\n", elevatorId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            doorLock.unlock();
        }
    }

    private void scheduleAutoClose() {
        doorTimer.schedule(() -> {
            if (doorState == DoorState.OPEN) {
                closeDoors();
            }
        }, openDurationMs, TimeUnit.MILLISECONDS);
    }

    public DoorState getDoorState() { return doorState; }

    public void shutdown() {
        doorTimer.shutdown();
    }
}

// Elevator Class (Runs on separate thread)
class Elevator implements Runnable {
    private final int elevatorId;
    private volatile int currentFloor;
    private volatile ElevatorState state;
    private volatile Direction direction;
    private final TreeSet<Integer> upRequests;
    private final TreeSet<Integer> downRequests;
    private final Thread elevatorThread;
    private final ReentrantLock elevatorLock;
    private final Condition requestCondition;
    private volatile boolean running;
    private final int capacity;
    private final AtomicInteger currentLoad;
    private final DoorController doorController;

    public Elevator(int id, int capacity) {
        this.elevatorId = id;
        this.currentFloor = 0;
        this.state = ElevatorState.IDLE;
        this.direction = Direction.NONE;
        this.upRequests = new TreeSet<>();
        this.downRequests = new TreeSet<>(Collections.reverseOrder());
        this.elevatorLock = new ReentrantLock();
        this.requestCondition = elevatorLock.newCondition();
        this.running = false;
        this.capacity = capacity;
        this.currentLoad = new AtomicInteger(0);
        this.doorController = new DoorController(id);
        this.elevatorThread = new Thread(this, "Elevator-" + id);
    }

    public void start() {
        running = true;
        elevatorThread.start();
        System.out.printf("[Elevator %d] Started at floor %d\n", elevatorId, currentFloor);
    }

    public void stop() {
        running = false;
        elevatorLock.lock();
        try {
            requestCondition.signalAll();
        } finally {
            elevatorLock.unlock();
        }
        doorController.shutdown();
    }

    @Override
    public void run() {
        while (running) {
            elevatorLock.lock();
            try {
                while (running && upRequests.isEmpty() && downRequests.isEmpty()) {
                    state = ElevatorState.IDLE;
                    direction = Direction.NONE;
                    requestCondition.await(1, TimeUnit.SECONDS);
                }

                if (!running) break;

                processRequests();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } finally {
                elevatorLock.unlock();
            }
        }
        System.out.printf("[Elevator %d] Stopped\n", elevatorId);
    }

    private void processRequests() throws InterruptedException {
        if (direction == Direction.NONE) {
            if (!upRequests.isEmpty()) {
                direction = Direction.UP;
            } else if (!downRequests.isEmpty()) {
                direction = Direction.DOWN;
            }
        }

        if (direction == Direction.UP && !upRequests.isEmpty()) {
            Integer next = upRequests.ceiling(currentFloor);
            if (next != null) {
                moveToFloor(next);
                upRequests.remove(next);
            } else {
                direction = Direction.DOWN;
            }
        } else if (direction == Direction.DOWN && !downRequests.isEmpty()) {
            Integer next = downRequests.ceiling(currentFloor);
            if (next != null) {
                moveToFloor(next);
                downRequests.remove(next);
            } else {
                direction = Direction.UP;
            }
        }

        if (upRequests.isEmpty() && downRequests.isEmpty()) {
            direction = Direction.NONE;
            state = ElevatorState.IDLE;
        }
    }

    private void moveToFloor(int targetFloor) throws InterruptedException {
        while (currentFloor != targetFloor) {
            if (currentFloor < targetFloor) {
                state = ElevatorState.MOVING_UP;
                direction = Direction.UP;
                currentFloor++;
            } else {
                state = ElevatorState.MOVING_DOWN;
                direction = Direction.DOWN;
                currentFloor--;
            }

            System.out.printf("[Elevator %d] Moving %s - Current Floor: %d\n",
                    elevatorId, direction, currentFloor);
            Thread.sleep(1000); // Simulate travel time
        }

        state = ElevatorState.STOPPED;
        System.out.printf("[Elevator %d] ARRIVED at Floor %d\n", elevatorId, currentFloor);

        doorController.openDoors();
        Thread.sleep(2000); // Wait for passengers
        doorController.closeDoors();
    }

    public void addInternalRequest(int floor) {
        elevatorLock.lock();
        try {
            if (floor > currentFloor) {
                upRequests.add(floor);
            } else if (floor < currentFloor) {
                downRequests.add(floor);
            }
            requestCondition.signal();
            System.out.printf("[Elevator %d] Internal request: Floor %d\n", elevatorId, floor);
        } finally {
            elevatorLock.unlock();
        }
    }

    public void addExternalRequest(int floor, Direction dir) {
        elevatorLock.lock();
        try {
            if (dir == Direction.UP) {
                upRequests.add(floor);
            } else {
                downRequests.add(floor);
            }
            requestCondition.signal();
            System.out.printf("[Elevator %d] External request: Floor %d, Direction %s\n",
                    elevatorId, floor, dir);
        } finally {
            elevatorLock.unlock();
        }
    }

    public int getCurrentFloor() { return currentFloor; }
    public ElevatorState getState() { return state; }
    public Direction getDirection() { return direction; }
    public int getElevatorId() { return elevatorId; }
    public int getCurrentLoad() { return currentLoad.get(); }
}

// Floor Class
class Floor {
    private final int floorNumber;
    private final UpButton upButton;
    private final DownButton downButton;
    private final DisplayBoard displayBoard;
    private Building building;

    public Floor(int number, Building building) {
        this.floorNumber = number;
        this.building = building;
        this.upButton = new UpButton(number, this);
        this.downButton = new DownButton(number, this);
        this.displayBoard = new DisplayBoard(number);
    }

    public void pressUpButton() {
        upButton.press();
    }

    public void pressDownButton() {
        downButton.press();
    }

    public void notifyElevatorRequest(Direction direction) {
        if (building != null) {
            building.requestElevator(floorNumber, direction);
        }
    }

    public void updateDisplay(List<Elevator> elevators) {
        elevators.forEach(e -> {
            ElevatorDisplayInfo info = new ElevatorDisplayInfo(
                    e.getElevatorId(),
                    e.getCurrentFloor(),
                    e.getDirection(),
                    e.getState(),
                    0
            );
            displayBoard.updateElevatorInfo(e.getElevatorId(), info);
        });
    }

    public DisplayBoard getDisplayBoard() { return displayBoard; }
    public int getFloorNumber() { return floorNumber; }
}

// Scheduling Strategy Interface
interface SchedulingStrategy {
    Elevator selectElevator(List<Elevator> elevators, ExternalRequest request);
}

// Nearest Car Strategy
class NearestCarStrategy implements SchedulingStrategy {
    @Override
    public Elevator selectElevator(List<Elevator> elevators, ExternalRequest request) {
        return elevators.stream()
                .filter(e -> e.getState() != ElevatorState.MAINTENANCE)
                .min(Comparator.comparingInt(e ->
                        Math.abs(e.getCurrentFloor() - request.getSourceFloor())))
                .orElse(elevators.get(0));
    }
}

// Elevator Scheduler
class ElevatorScheduler {
    private final List<Elevator> elevators;
    private final SchedulingStrategy strategy;
    private final BlockingQueue<ExternalRequest> requestQueue;
    private final Thread dispatcherThread;
    private volatile boolean running;

    public ElevatorScheduler(List<Elevator> elevators, SchedulingStrategy strategy) {
        this.elevators = elevators;
        this.strategy = strategy;
        this.requestQueue = new LinkedBlockingQueue<>();
        this.running = false;
        this.dispatcherThread = new Thread(this::dispatchRequests, "Scheduler-Dispatcher");
    }

    public void start() {
        running = true;
        dispatcherThread.start();
    }

    public void stop() {
        running = false;
        dispatcherThread.interrupt();
    }

    public void scheduleRequest(ExternalRequest request) {
        try {
            requestQueue.put(request);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void dispatchRequests() {
        while (running) {
            try {
                ExternalRequest request = requestQueue.poll(1, TimeUnit.SECONDS);
                if (request != null) {
                    Elevator selected = strategy.selectElevator(elevators, request);
                    selected.addExternalRequest(request.getSourceFloor(), request.getDirection());
                    System.out.printf("[Scheduler] Assigned Elevator %d for Floor %d (%s)\n",
                            selected.getElevatorId(), request.getSourceFloor(), request.getDirection());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}

// Building Class
class Building {
    private final String buildingId;
    private final int totalFloors;
    private final List<Elevator> elevators;
    private final List<Floor> floors;
    private final ElevatorScheduler scheduler;
    private final ScheduledExecutorService displayUpdater;

    public Building(String id, int floors, int elevatorCount) {
        this.buildingId = id;
        this.totalFloors = floors;
        this.elevators = new ArrayList<>();
        this.floors = new ArrayList<>();
        this.displayUpdater = Executors.newSingleThreadScheduledExecutor();

        initializeElevators(elevatorCount);
        initializeFloors();

        this.scheduler = new ElevatorScheduler(elevators, new NearestCarStrategy());
        this.scheduler.start();

        startDisplayUpdates();
    }

    private void initializeElevators(int count) {
        for (int i = 0; i < count; i++) {
            Elevator elevator = new Elevator(i + 1, 10);
            elevators.add(elevator);
            elevator.start();
        }
    }

    private void initializeFloors() {
        for (int i = 0; i < totalFloors; i++) {
            floors.add(new Floor(i, this));
        }
    }

    private void startDisplayUpdates() {
        displayUpdater.scheduleAtFixedRate(() -> {
            floors.forEach(floor -> floor.updateDisplay(elevators));
        }, 0, 3, TimeUnit.SECONDS);
    }

    public void requestElevator(int floor, Direction direction) {
        ExternalRequest request = new ExternalRequest(floor, direction);
        scheduler.scheduleRequest(request);
    }

    public Floor getFloor(int floorNumber) {
        return floors.get(floorNumber);
    }

    public Elevator getElevator(int elevatorId) {
        return elevators.get(elevatorId - 1);
    }

    public void shutdown() {
        scheduler.stop();
        elevators.forEach(Elevator::stop);
        displayUpdater.shutdown();
    }
}

// Elevator Controller (Singleton)
class ElevatorController {
    private static volatile ElevatorController instance;
    private Building building;
    private volatile boolean systemRunning;

    private ElevatorController() {}

    public static ElevatorController getInstance() {
        if (instance == null) {
            synchronized (ElevatorController.class) {
                if (instance == null) {
                    instance = new ElevatorController();
                }
            }
        }
        return instance;
    }

    public void initializeSystem(int floors, int elevatorCount) {
        building = new Building("MAIN", floors, elevatorCount);
        systemRunning = true;
        System.out.printf("Elevator System Initialized: %d floors, %d elevators\n",
                floors, elevatorCount);
    }

    public void requestElevator(int floor, Direction direction) {
        if (building != null) {
            building.requestElevator(floor, direction);
        }
    }

    public void sendInternalRequest(int elevatorId, int floor) {
        if (building != null) {
            building.getElevator(elevatorId).addInternalRequest(floor);
        }
    }

    public void shutdown() {
        systemRunning = false;
        if (building != null) {
            building.shutdown();
        }
    }
}

// Main Demo Class
public class ElevatorSystemDemo {
    public static void main(String[] args) throws InterruptedException {
        ElevatorController controller = ElevatorController.getInstance();
        controller.initializeSystem(10, 5);

        Thread.sleep(2000);

        // Simulate requests
        System.out.println("\n=== Simulating Elevator Requests ===\n");

        controller.requestElevator(5, Direction.UP);
        Thread.sleep(1000);

        controller.requestElevator(3, Direction.DOWN);
        Thread.sleep(1000);

        controller.requestElevator(8, Direction.UP);
        Thread.sleep(2000);

        controller.sendInternalRequest(1, 7);
        Thread.sleep(1000);

        controller.sendInternalRequest(2, 9);

        // Let system run for a while
        Thread.sleep(30000);

        controller.shutdown();
        System.out.println("\nSystem Shutdown Complete");
    }
}