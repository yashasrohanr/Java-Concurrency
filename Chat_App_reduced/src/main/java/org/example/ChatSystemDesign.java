package org.example;

import java.util.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

// ==========================================
// 1. ENUMS & INTERFACES (Abstractions)
// ==========================================

// REFACTORED: Removed MessageType Enum.
// Introduced polymorphic Content classes instead.

abstract class MessageContent {
    // Each content type decides how it should be previewed/displayed
    public abstract String getDisplayData();
}

class TextContent extends MessageContent {
    private String text;

    public TextContent(String text) {
        this.text = text;
    }

    @Override
    public String getDisplayData() {
        return text;
    }
}

class ImageContent extends MessageContent {
    private String url;
    private long sizeBytes; // Example of metadata specific to media

    public ImageContent(String url, long sizeBytes) {
        this.url = url;
        this.sizeBytes = sizeBytes;
    }

    @Override
    public String getDisplayData() {
        return "[Image: " + url + " (" + (sizeBytes/1024) + "KB)]";
    }
}

// Observer Pattern: Observer
interface INotificationObserver {
    void onNewMessage(Message message, ChatThread thread);
}

// Strategy/Interface for Media (Camera/Gallery)
interface IMediaController {
    void takePicture();
    void selectFromGallery();
}

// Implementation of Media Controller
class PhoneMediaController implements IMediaController {
    @Override
    public void takePicture() {
        System.out.println("[Camera Hardware] Capturing image...");
    }

    @Override
    public void selectFromGallery() {
        System.out.println("[Gallery API] Selecting image from storage...");
    }
}

// ==========================================
// 2. CORE ENTITIES
// ==========================================

class User implements INotificationObserver, Comparable<User> {
    private String userId;
    private String name;

    public User(String userId, String name) {
        this.userId = userId;
        this.name = name;
    }

    public String getName() { return name; }
    public String getId() { return userId; }

    // Observer Callback
    @Override
    public void onNewMessage(Message message, ChatThread thread) {
        // In a real app, this might push a system notification
        if (!message.getSender().getId().equals(this.userId)) {
            System.out.println(">> NOTIFICATION for " + this.name + ": New message in "
                    + thread.getChatName() + " from " + message.getSender().getName());
        }
    }

    @Override
    public String toString() { return name; }

    @Override
    public int compareTo(User o) { return this.name.compareTo(o.name); }
}

class Message {
    private String id;
    private User sender;
    private MessageContent content; // REFACTORED: Uses abstract base class
    private LocalDateTime timestamp;

    public Message(User sender, MessageContent content) {
        this.id = UUID.randomUUID().toString();
        this.sender = sender;
        this.content = content;
        this.timestamp = LocalDateTime.now();
    }

    public LocalDateTime getTimestamp() { return timestamp; }
    public User getSender() { return sender; }

    @Override
    public String toString() {
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("HH:mm:ss");
        // Polymorphism: content.getDisplayData() behaves differently for Text vs Image
        return "[" + timestamp.format(dtf) + "] " + sender.getName() + ": " + content.getDisplayData();
    }
}

// ==========================================
// 3. CHAT LOGIC (Subject in Observer Pattern)
// ==========================================

abstract class ChatThread implements Comparable<ChatThread> {
    protected String chatId;
    protected List<Message> messages;
    protected List<INotificationObserver> observers; // Users listening to this chat

    public ChatThread() {
        this.chatId = UUID.randomUUID().toString();
        this.messages = new ArrayList<>();
        this.observers = new ArrayList<>();
    }

    public void addObserver(INotificationObserver observer) {
        observers.add(observer);
    }

    public void addMessage(Message message) {
        messages.add(message);
        notifyObservers(message);
    }

    protected void notifyObservers(Message message) {
        for (INotificationObserver observer : observers) {
            observer.onNewMessage(message, this);
        }
    }

    public Message getLastMessage() {
        if (messages.isEmpty()) return null;
        return messages.getLast();
    }

    // For sorting in the Feed (Priority Queue Logic)
    @Override
    public int compareTo(ChatThread other) {
        Message myLast = this.getLastMessage();
        Message otherLast = other.getLastMessage();

        if (myLast == null) return 1;
        if (otherLast == null) return -1;

        // Descending order (Newest first)
        return otherLast.getTimestamp().compareTo(myLast.getTimestamp());
    }

    public abstract String getChatName();
}

class PrivateChat extends ChatThread {
    private User user1;
    private User user2;

    public PrivateChat(User u1, User u2) {
        super();
        this.user1 = u1;
        this.user2 = u2;
        // Automatically subscribe participants to notifications
        addObserver(u1);
        addObserver(u2);
    }

    @Override
    public String getChatName() {
        return user1.getName() + " & " + user2.getName();
    }
}

class GroupChat extends ChatThread {
    private String groupName;
    private List<User> participants;
    private User admin;

    public GroupChat(String groupName, User admin) {
        super();
        this.groupName = groupName;
        this.admin = admin;
        this.participants = new ArrayList<>();
        addParticipant(admin);
    }

    public void addParticipant(User user) {
        participants.add(user);
        addObserver(user); // Subscribe to notifications
    }

    @Override
    public String getChatName() {
        return groupName + " (Group)";
    }
}

// ==========================================
// 4. CONTROLLERS & SYSTEMS
// ==========================================

class ChatFeed {
    private User currentUser;
    private List<ChatThread> allThreads;

    public ChatFeed(User currentUser, List<ChatThread> allThreads) {
        this.currentUser = currentUser;
        this.allThreads = allThreads;
    }

    // The "Priority Queue" requirement:
    // We return the list sorted by the most recent message.
    public void printFeed() {
        System.out.println("\n--- Chat Feed for " + currentUser.getName() + " ---");

        // In a real system, we might use a PriorityQueue wrapper,
        // but sorting a list on view is often cleaner for mutable timestamps.
        Collections.sort(allThreads);

        for (ChatThread thread : allThreads) {
            Message lastMsg = thread.getLastMessage();
            String preview = (lastMsg != null) ? lastMsg.toString() : "No messages";
            System.out.println(thread.getChatName() + " | " + preview);
        }
        System.out.println("---------------------------------");
    }
}

// Singleton Main System
class WhatsAppSystem {
    private static WhatsAppSystem instance;
    private Map<String, User> users;
    private Map<String, ChatThread> chats;
    private IMediaController mediaController;

    private WhatsAppSystem() {
        users = new HashMap<>();
        chats = new HashMap<>();
        mediaController = new PhoneMediaController();
    }

    public static synchronized WhatsAppSystem getInstance() {
        if (instance == null) instance = new WhatsAppSystem();
        return instance;
    }

    public User registerUser(String name) {
        User user = new User(UUID.randomUUID().toString(), name);
        users.put(user.getId(), user);
        return user;
    }

    public ChatThread createPrivateChat(User u1, User u2) {
        ChatThread chat = new PrivateChat(u1, u2);
        chats.put(chat.chatId, chat);
        return chat;
    }

    public GroupChat createGroupChat(String name, User admin) {
        GroupChat group = new GroupChat(name, admin);
        chats.put(group.chatId, group);
        return (GroupChat) group; // Return casted for ease of adding participants
    }

    // Feature: Send Image (simulated)
    public void sendImage(ChatThread chat, User sender) {
        mediaController.takePicture(); // Simulate camera access

        // In real life, upload to server, get URL. Here we mock the URL.
        String mockUrl = "https://s3.aws.com/photo.jpg";

        // REFACTORED: Create specific content object
        MessageContent imageContent = new ImageContent(mockUrl, 2048); // 2MB
        Message msg = new Message(sender, imageContent);

        chat.addMessage(msg);
    }

    public void sendText(ChatThread chat, User sender, String text) {
        // REFACTORED: Create specific content object
        MessageContent textContent = new TextContent(text);
        Message msg = new Message(sender, textContent);

        chat.addMessage(msg);
    }

    // Helper to simulate the "View Feed" feature
    public ChatFeed getFeedForUser(User user) {
        // In a real app, this would query DB for chats where User is a participant
        // Here we just filter the memory map for demo purposes
        List<ChatThread> userChats = new ArrayList<>();
        for(ChatThread chat : chats.values()) {
            // This is a simplified check. Real design would have user_id logic inside chat
            if(chat.observers.contains(user)) {
                userChats.add(chat);
            }
        }
        return new ChatFeed(user, userChats);
    }
}

// ==========================================
// 5. DRIVER CODE
// ==========================================
public class ChatSystemDesign {
    public static void main(String[] args) throws InterruptedException {
        WhatsAppSystem app = WhatsAppSystem.getInstance();

        // 1. Create Users
        User alice = app.registerUser("Alice");
        User bob = app.registerUser("Bob");
        User charlie = app.registerUser("Charlie");

        // 2. Create Chats
        ChatThread chatAliceBob = app.createPrivateChat(alice, bob);
        GroupChat groupFun = app.createGroupChat("Weekend Trip", alice);
        groupFun.addParticipant(bob);
        groupFun.addParticipant(charlie);

        // 3. Simulation: Messaging and Sorting

        System.out.println("=== SCENARIO 1: Alice messages Bob ===");
        app.sendText(chatAliceBob, alice, "Hey Bob, how are you?");
        Thread.sleep(100); // Artificial delay to make timestamps distinct

        System.out.println("\n=== SCENARIO 2: Bob replies with an Image ===");
        app.sendImage(chatAliceBob, bob);
        Thread.sleep(100);

        System.out.println("\n=== SCENARIO 3: Charlie messages the Group ===");
        app.sendText(groupFun, charlie, "Anyone up for hiking?");

        // 4. Check Feeds (Priority Queue Logic)
        // Alice should see the Group chat first (because Charlie messaged last)
        // and then her chat with Bob.
        ChatFeed aliceFeed = app.getFeedForUser(alice);
        aliceFeed.printFeed();

        // 5. Update Private Chat again
        System.out.println("\n=== SCENARIO 4: Alice replies to Bob (Private) ===");
        Thread.sleep(100);
        app.sendText(chatAliceBob, alice, "Nice photo!");

        // Now Private chat should move to top of Alice's feed
        aliceFeed.printFeed();
    }
}