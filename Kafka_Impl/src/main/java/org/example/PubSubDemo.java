package org.example;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

// ==================== Message Model ====================
class Message {
    private final String id;
    private final String payload;
    private final long timestamp;
    private final String topicName;

    Message(String id, String payload, String topicName) {
        this.id = id;
        this.payload = payload;
        this.timestamp = System.currentTimeMillis();
        this.topicName = topicName;
    }

    String getId() { return id; }
    String getPayload() { return payload; }
    long getTimestamp() { return timestamp; }
    String getTopicName() { return topicName; }

    @Override
    public String toString() {
        return "Message{" + "id='" + id + '\'' + ", payload='" + payload + '\'' +
                ", timestamp=" + timestamp + '}';
    }
}

// ==================== Publisher Interface ====================
interface IPublisher {
    void publish(String topicName, String message);
}

// ==================== Subscriber Interface ====================
interface ISubscriber {
    void onMessage(Message message);

    default String getSubscriberName() {
        return "DefaultSubscriber";
    }
}

// ==================== Topic Partition (for ordering & scaling) ====================
class TopicPartition {
    private final int partitionId;
    private final LinkedList<Message> messages;
    private final ReentrantReadWriteLock lock;
    private static final int MAX_PARTITION_SIZE = 100000;

    TopicPartition(int partitionId) {
        this.partitionId = partitionId;
        this.messages = new LinkedList<>();
        this.lock = new ReentrantReadWriteLock();
    }

    void addMessage(Message msg) {
        lock.writeLock().lock();
        try {
            if (messages.size() >= MAX_PARTITION_SIZE) {
                messages.removeFirst();
            }
            messages.add(msg);
        } finally {
            lock.writeLock().unlock();
        }
    }

    Message getMessageAt(int index) {
        lock.readLock().lock();
        try {
            if (index >= 0 && index < messages.size()) {
                return messages.get(index);
            }
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    int getMessageCount() {
        lock.readLock().lock();
        try {
            return messages.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    List<Message> getMessagesFrom(int offset) {
        lock.readLock().lock();
        try {
            if (offset >= messages.size()) {
                return new ArrayList<>();
            }
            return new ArrayList<>(messages.subList(Math.max(0, offset), messages.size()));
        } finally {
            lock.readLock().unlock();
        }
    }

    int getPartitionId() { return partitionId; }
}

// ==================== Topic ====================
class Topic {
    private final String name;
    private final int numPartitions;
    private final Map<Integer, TopicPartition> partitions;
    private final ReentrantReadWriteLock lock;

    Topic(String name, int numPartitions) {
        this.name = name;
        this.numPartitions = numPartitions;
        this.partitions = new ConcurrentHashMap<>();
        this.lock = new ReentrantReadWriteLock();

        for (int i = 0; i < numPartitions; i++) {
            partitions.put(i, new TopicPartition(i));
        }
    }

    void publishMessage(Message msg) {
        int partitionId = Math.abs(msg.getId().hashCode()) % numPartitions;
        TopicPartition partition = partitions.get(partitionId);
        partition.addMessage(msg);
    }

    TopicPartition getPartition(int partitionId) {
        return partitions.get(partitionId);
    }

    int getNumPartitions() { return numPartitions; }
    String getName() { return name; }
    Collection<TopicPartition> getAllPartitions() { return partitions.values(); }
}

// ==================== Consumer Group (New Addition) ====================
class ConsumerGroup {
    private final String groupId;
    private final Map<Integer, Integer> partitionLeaders; // partition -> consumer index
    private final List<String> consumers;
    private final ReentrantReadWriteLock lock;
    private final int numPartitions;

    ConsumerGroup(String groupId, int numPartitions) {
        this.groupId = groupId;
        this.numPartitions = numPartitions;
        this.consumers = new CopyOnWriteArrayList<>();
        this.partitionLeaders = new ConcurrentHashMap<>();
        this.lock = new ReentrantReadWriteLock();
        initializePartitionAssignment();
    }

    private void initializePartitionAssignment() {
        for (int i = 0; i < numPartitions; i++) {
            partitionLeaders.put(i, 0);
        }
    }

    void addConsumer(String consumerId) {
        lock.writeLock().lock();
        try {
            if (!consumers.contains(consumerId)) {
                consumers.add(consumerId);
                rebalancePartitions();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    void removeConsumer(String consumerId) {
        lock.writeLock().lock();
        try {
            if (consumers.remove(consumerId)) {
                rebalancePartitions();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void rebalancePartitions() {
        if (consumers.isEmpty()) return;

        for (int i = 0; i < numPartitions; i++) {
            int assignedConsumerIndex = i % consumers.size();
            partitionLeaders.put(i, assignedConsumerIndex);
        }
    }

    String getConsumerForPartition(int partitionId) {
        lock.readLock().lock();
        try {
            Integer consumerIndex = partitionLeaders.get(partitionId);
            if (consumerIndex != null && consumerIndex < consumers.size()) {
                return consumers.get(consumerIndex);
            }
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    String getGroupId() { return groupId; }
    int getConsumerCount() { return consumers.size(); }
}

// ==================== Topic Subscriber (Offset Tracking) ====================
class TopicSubscriber {
    private final String subscriberId;
    private final ISubscriber subscriber;
    private final Map<Integer, Integer> partitionOffsets;
    private final ReentrantReadWriteLock offsetLock;
    private volatile boolean active;

    TopicSubscriber(String subscriberId, ISubscriber subscriber, int numPartitions) {
        this.subscriberId = subscriberId;
        this.subscriber = subscriber;
        this.partitionOffsets = new ConcurrentHashMap<>();
        this.offsetLock = new ReentrantReadWriteLock();
        this.active = true;

        for (int i = 0; i < numPartitions; i++) {
            partitionOffsets.put(i, 0);
        }
    }

    void updateOffset(int partitionId, int offset) {
        offsetLock.writeLock().lock();
        try {
            partitionOffsets.put(partitionId, offset);
        } finally {
            offsetLock.writeLock().unlock();
        }
    }

    int getOffset(int partitionId) {
        offsetLock.readLock().lock();
        try {
            return partitionOffsets.getOrDefault(partitionId, 0);
        } finally {
            offsetLock.readLock().unlock();
        }
    }

    String getSubscriberId() { return subscriberId; }
    ISubscriber getSubscriber() { return subscriber; }
    void setActive(boolean active) { this.active = active; }
    boolean isActive() { return active; }
}

// ==================== Message Delivery Thread ====================
class PartitionConsumer implements Runnable {
    private final TopicPartition partition;
    private final TopicSubscriber topicSubscriber;
    private final ExecutorService callbackExecutor;
    private volatile boolean running;

    PartitionConsumer(TopicPartition partition, TopicSubscriber topicSubscriber,
                      ExecutorService callbackExecutor) {
        this.partition = partition;
        this.topicSubscriber = topicSubscriber;
        this.callbackExecutor = callbackExecutor;
        this.running = true;
    }

    @Override
    public void run() {
        while (running && topicSubscriber.isActive()) {
            try {
                int currentOffset = topicSubscriber.getOffset(partition.getPartitionId());
                int messageCount = partition.getMessageCount();

                if (currentOffset < messageCount) {
                    Message msg = partition.getMessageAt(currentOffset);
                    if (msg != null) {
                        callbackExecutor.submit(() -> {
                            try {
                                topicSubscriber.getSubscriber().onMessage(msg);
                                topicSubscriber.updateOffset(partition.getPartitionId(),
                                        currentOffset + 1);
                            } catch (Exception e) {
                                System.err.println("Error processing message: " + e.getMessage());
                            }
                        });
                    }
                } else {
                    Thread.sleep(100);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    void stop() { running = false; }
}

// ==================== Main Kafka Controller ====================
class KafkaController implements IPublisher {
    private final Map<String, Topic> topics;
    private final Map<String, Map<String, TopicSubscriber>> topicSubscribers;
    private final Map<String, ConsumerGroup> consumerGroups;
    private final Map<String, ExecutorService> subscriberExecutors;
    private final Map<String, List<PartitionConsumer>> activeConsumers;
    private final ReentrantReadWriteLock controllerLock;
    private final ExecutorService publisherExecutor;
    private final int defaultPartitions;

    KafkaController(int numThreads, int defaultPartitions) {
        this.topics = new ConcurrentHashMap<>();
        this.topicSubscribers = new ConcurrentHashMap<>();
        this.consumerGroups = new ConcurrentHashMap<>();
        this.subscriberExecutors = new ConcurrentHashMap<>();
        this.activeConsumers = new ConcurrentHashMap<>();
        this.controllerLock = new ReentrantReadWriteLock();
        this.publisherExecutor = Executors.newFixedThreadPool(numThreads);
        this.defaultPartitions = defaultPartitions;
    }

    void createTopic(String topicName) {
        createTopic(topicName, defaultPartitions);
    }

    void createTopic(String topicName, int numPartitions) {
        controllerLock.writeLock().lock();
        try {
            if (!topics.containsKey(topicName)) {
                topics.put(topicName, new Topic(topicName, numPartitions));
                topicSubscribers.put(topicName, new ConcurrentHashMap<>());
                System.out.println("Topic created: " + topicName +
                        " with " + numPartitions + " partitions");
            }
        } finally {
            controllerLock.writeLock().unlock();
        }
    }

    void createConsumerGroup(String groupId, String topicName) {
        controllerLock.writeLock().lock();
        try {
            Topic topic = topics.get(topicName);
            if (topic == null) {
                System.err.println("Topic not found: " + topicName);
                return;
            }

            String key = topicName + "-" + groupId;
            if (!consumerGroups.containsKey(key)) {
                consumerGroups.put(key, new ConsumerGroup(groupId, topic.getNumPartitions()));
                System.out.println("Consumer group created: " + groupId + " for topic: " + topicName);
            }
        } finally {
            controllerLock.writeLock().unlock();
        }
    }

    @Override
    public void publish(String topicName, String messagePayload) {
        publisherExecutor.submit(() -> {
            controllerLock.readLock().lock();
            try {
                Topic topic = topics.get(topicName);
                if (topic != null) {
                    String msgId = UUID.randomUUID().toString();
                    Message msg = new Message(msgId, messagePayload, topicName);
                    topic.publishMessage(msg);
                    System.out.println("Published to " + topicName + ": " + msg);
                } else {
                    System.err.println("Topic not found: " + topicName);
                }
            } finally {
                controllerLock.readLock().unlock();
            }
        });
    }

    void subscribe(String topicName, String subscriberId, ISubscriber subscriber) {
        controllerLock.writeLock().lock();
        try {
            Topic topic = topics.get(topicName);
            if (topic == null) {
                System.err.println("Topic not found: " + topicName);
                return;
            }

            Map<String, TopicSubscriber> subscribers = topicSubscribers.get(topicName);
            if (subscribers.containsKey(subscriberId)) {
                System.out.println("Subscriber already exists: " + subscriberId);
                return;
            }

            TopicSubscriber topicSub = new TopicSubscriber(subscriberId, subscriber,
                    topic.getNumPartitions());
            subscribers.put(subscriberId, topicSub);

            ExecutorService subExecutor = subscriberExecutors.computeIfAbsent(
                    subscriberId, k -> Executors.newFixedThreadPool(topic.getNumPartitions())
            );

            List<PartitionConsumer> consumers = new ArrayList<>();
            for (TopicPartition partition : topic.getAllPartitions()) {
                PartitionConsumer consumer = new PartitionConsumer(partition, topicSub,
                        subExecutor);
                subExecutor.submit(consumer);
                consumers.add(consumer);
            }

            activeConsumers.put(subscriberId + "-" + topicName, consumers);
            System.out.println("Subscriber " + subscriberId + " subscribed to " + topicName);
        } finally {
            controllerLock.writeLock().unlock();
        }
    }

    void subscribeToGroup(String topicName, String groupId, String consumerId, ISubscriber subscriber) {
        controllerLock.writeLock().lock();
        try {
            Topic topic = topics.get(topicName);
            if (topic == null) {
                System.err.println("Topic not found: " + topicName);
                return;
            }

            String groupKey = topicName + "-" + groupId;
            ConsumerGroup group = consumerGroups.get(groupKey);
            if (group == null) {
                System.err.println("Consumer group not found: " + groupId);
                return;
            }

            group.addConsumer(consumerId);

            Map<String, TopicSubscriber> subscribers = topicSubscribers.get(topicName);
            String subKey = consumerId + "-" + groupId;

            if (!subscribers.containsKey(subKey)) {
                TopicSubscriber topicSub = new TopicSubscriber(subKey, subscriber,
                        topic.getNumPartitions());
                subscribers.put(subKey, topicSub);

                ExecutorService subExecutor = subscriberExecutors.computeIfAbsent(
                        subKey, k -> Executors.newFixedThreadPool(1)
                );

                List<PartitionConsumer> consumers = new ArrayList<>();
                for (TopicPartition partition : topic.getAllPartitions()) {
                    String assignedConsumer = group.getConsumerForPartition(partition.getPartitionId());
                    if (assignedConsumer != null && assignedConsumer.equals(consumerId)) {
                        PartitionConsumer consumer = new PartitionConsumer(partition, topicSub, subExecutor);
                        subExecutor.submit(consumer);
                        consumers.add(consumer);
                    }
                }

                activeConsumers.put(subKey + "-" + topicName, consumers);
                System.out.println("Consumer " + consumerId + " added to group " + groupId +
                        " with " + consumers.size() + " partitions assigned");
            }
        } finally {
            controllerLock.writeLock().unlock();
        }
    }

    void unsubscribe(String topicName, String subscriberId) {
        controllerLock.writeLock().lock();
        try {
            Map<String, TopicSubscriber> subscribers = topicSubscribers.get(topicName);
            if (subscribers != null) {
                TopicSubscriber topicSub = subscribers.remove(subscriberId);
                if (topicSub != null) {
                    topicSub.setActive(false);
                }
            }

            String key = subscriberId + "-" + topicName;
            List<PartitionConsumer> consumers = activeConsumers.remove(key);
            if (consumers != null) {
                consumers.forEach(PartitionConsumer::stop);
            }

            System.out.println("Subscriber " + subscriberId + " unsubscribed from " + topicName);
        } finally {
            controllerLock.writeLock().unlock();
        }
    }

    void shutdown() {
        controllerLock.writeLock().lock();
        try {
            activeConsumers.values().forEach(consumers ->
                    consumers.forEach(PartitionConsumer::stop)
            );
            publisherExecutor.shutdown();
            subscriberExecutors.values().forEach(ExecutorService::shutdown);
            System.out.println("Kafka Controller shutdown");
        } finally {
            controllerLock.writeLock().unlock();
        }
    }

    int getSubscriberCount(String topicName) {
        controllerLock.readLock().lock();
        try {
            Map<String, TopicSubscriber> subscribers = topicSubscribers.get(topicName);
            return subscribers != null ? subscribers.size() : 0;
        } finally {
            controllerLock.readLock().unlock();
        }
    }
}

// ==================== Example Usage ====================
public class PubSubDemo {
    public static void main(String[] args) throws InterruptedException {
        KafkaController kafka = getKafkaController();

        ISubscriber groupSub1 = msg -> System.out.println("[Group-Consumer-1] Received: " + msg.getPayload());
        ISubscriber groupSub2 = msg -> System.out.println("[Group-Consumer-2] Received: " + msg.getPayload());

        kafka.subscribeToGroup("orders", "payment-group", "consumer-1", groupSub1);
        kafka.subscribeToGroup("orders", "payment-group", "consumer-2", groupSub2);

        Thread.sleep(500);

        for (int i = 0; i < 10; i++) {
            kafka.publish("orders", "Order-" + i);
        }

        Thread.sleep(2000);
        kafka.shutdown();
    }

    private static KafkaController getKafkaController() {
        KafkaController kafka = new KafkaController(4, 3);

        kafka.createTopic("orders", 3);

        // Direct subscription
        ISubscriber subscriber1 = new ISubscriber() {
            @Override
            public void onMessage(Message msg) {
                System.out.println("[Direct-Sub-1] Received: " + msg.getPayload());
            }
            @Override
            public String getSubscriberName() { return "Direct-Sub-1"; }
        };

        kafka.subscribe("orders", "direct-1", subscriber1);

        // Consumer group subscription
        kafka.createConsumerGroup("payment-group", "orders");
        return kafka;
    }
}