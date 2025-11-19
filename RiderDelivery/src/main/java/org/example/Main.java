package org.example;

enum DriverState {
    idle,
    movingForPickup,
    delivering
}

enum OrderStatus {
    GettingReady,
    ReadyForPickup,
    PickedUp,
    Delivered
}

enum DriverMatch {
    Offered,
    Accepted,
    Rejected,
}

class Order {
    String orderId;
    String pickupLocation;
    String dropLocation;
    Driver currDriver;
    String metadata;
    OrderStatus status;
    DriverMatch deliveryStatus;
    public Order(String id, String pl, String dl, String met) {
        this.pickupLocation = pl;
        this.dropLocation = dl;
        this.metadata = met;
        this.orderId = id;
    }

}


class Driver {
    private long id;
    private String currLocation;
    private DriverState currState;
    private Order currOrder;
    public Driver(long id, String currLocation) {
        this.id = id;
        this.currLocation = currLocation;
        this.currState = DriverState.idle;
        this.currOrder = null;
    }

    boolean acceptOrder(Order order) {
//        if(currOrder != null) {
//            System.out.println("Driver " );
//        }
        if(currState != DriverState.idle) return false;
        System.out.println("Driver " + id + " accepted " + order.orderId);
        currOrder = order;
        currState = DriverState.movingForPickup;

        return true;
    }

    boolean rejectOrder(Order order) {
        System.out.println("Driver " + id + " rejected " + order.orderId);
        return true;
    }

    boolean pickupOrder(){
        if(currOrder == null) {
            System.out.println("No order no pickup !!");
            return false;
        }
        currLocation = currOrder.pickupLocation;
        currOrder.status = OrderStatus.PickedUp;
        currState = DriverState.delivering;
        System.out.println("Driver " + id + " picked up " + currOrder.orderId);
        return true;
    }

    boolean deliverOrder() {
        System.out.println("Delivered order" + currOrder.orderId);
        currLocation = currOrder.pickupLocation;
        currOrder.status = OrderStatus.Delivered;
        currState = DriverState.idle;

        return true;
    }
}


public class Main {
    public static void main(String[] args) {
        System.out.println("Hello, World!");

        // one driver
        Driver driver = new Driver(1, "L3");

        // generate random orders
        Order order1 = new Order("1", "L1", "L3", "Order - m1");
        Order order2 = new Order("2", "L1", "L3", "Order - m1");
        Order order3 = new Order("3", "L1", "L3", "Order - m1");

        //  choose

        driver.rejectOrder(order1);
        driver.pickupOrder();

        driver.acceptOrder(order2);

        driver.pickupOrder();

        driver.acceptOrder(order3);

        driver.deliverOrder();
    }
}


100 coins     10H      90T

      x, n - x

    90
    2      88

            
    10
    2     8
