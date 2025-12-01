package org.example;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Single-file LLD for Real-Time Chat System suitable for SDE-3 interview.
 * Public class: RealTimeChatSystem
 *
 * - One public class in file (RealTimeChatSystem)
 * - All other classes are package-private (no 'public' modifier)
 * - Simple in-memory repository & notification implementations
 * - Per-recipient delivery/read states, conversation sequence numbers, basic pagination
 */

// ===================== ENUMS =====================
enum MessageStatus {
    SENT, DELIVERED, READ
}

enum UserStatus {
    ONLINE, OFFLINE, AWAY
}

enum ChatType {
    ONE_ON_ONE, GROUP
}

// ===================== DOMAIN ENTITIES =====================
class User {
    private final String userId;
    private String name;
    private volatile UserStatus status;

    public User(String userId, String name) {
        this.userId = userId;
        this.name = name;
        this.status = UserStatus.OFFLINE;
    }

    public String getId() { return userId; }
    public String getName() { return name; }
    public UserStatus getStatus() { return status; }
    public void setStatus(UserStatus status) { this.status = status; }
}

class Message {
    private final String messageId;
    private final String senderId;
    private final String conversationId;
    private final String content;
    private final LocalDateTime timestamp;
    private final long seqNo; // per conversation sequence number

    // per-recipient status map (recipientId -> status)
    private final ConcurrentHashMap<String, MessageStatus> perRecipientStatus = new ConcurrentHashMap<>();

    public Message(String messageId, String senderId, String conversationId, String content, long seqNo, Collection<String> recipients) {
        this.messageId = messageId;
        this.senderId = senderId;
        this.conversationId = conversationId;
        this.content = content;
        this.timestamp = LocalDateTime.now();
        this.seqNo = seqNo;
        // initialize statuses to SENT for recipients (excluding sender)
        for (String r : recipients) {
            if (!r.equals(senderId)) perRecipientStatus.put(r, MessageStatus.SENT);
        }
    }

    public String getId() { return messageId; }
    public String getSenderId() { return senderId; }
    public String getConversationId() { return conversationId; }
    public String getContent() { return content; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public long getSeqNo() { return seqNo; }

    public MessageStatus getStatusFor(String userId) {
        return perRecipientStatus.get(userId);
    }

    public void setStatusFor(String userId, MessageStatus status) {
        if (perRecipientStatus.containsKey(userId)) perRecipientStatus.put(userId, status);
    }

    public Map<String, MessageStatus> getPerRecipientStatuses() {
        return Collections.unmodifiableMap(perRecipientStatus);
    }

    @Override
    public String toString() {
        return String.format("msg[%s|seq=%d] %s -> %s : %s", messageId, seqNo, senderId, conversationId, content);
    }
}

abstract class Conversation {
    protected final String conversationId;
    protected final ChatType type;
    protected final CopyOnWriteArrayList<String> participants = new CopyOnWriteArrayList<>();
    // per conversation sequence number generator to provide ordering guarantees
    protected final AtomicInteger seqGenerator = new AtomicInteger(0);

    public Conversation(String id, ChatType type) {
        this.conversationId = id;
        this.type = type;
    }

    public String getId() { return conversationId; }
    public ChatType getType() { return type; }
    public List<String> getParticipants() { return participants; }
    public void addParticipant(String userId) { participants.addIfAbsent(userId); }
    public void removeParticipant(String userId) { participants.remove(userId); }

    public int nextSequence() { return seqGenerator.incrementAndGet(); }
}

class PrivateChat extends Conversation {
    public PrivateChat(String id, String user1, String user2) {
        super(id, ChatType.ONE_ON_ONE);
        addParticipant(user1);
        addParticipant(user2);
    }
}

class GroupChat extends Conversation {
    private final String groupName;
    public GroupChat(String id, String groupName, Collection<String> initialParticipants) {
        super(id, ChatType.GROUP);
        this.groupName = groupName;
        if (initialParticipants != null) initialParticipants.forEach(this::addParticipant);
    }
    public String getGroupName() { return groupName; }
}

// ===================== ABSTRACT LAYERS (INTERFACES) =====================

interface IChatRepository {
    void saveMessage(Message message);
    List<Message> getHistory(String conversationId, int limit, int offset); // simple pagination
    Conversation getConversation(String conversationId);
    void createConversation(Conversation conversation);
    User getUser(String userId);
    void saveUser(User user);
    long getConversationSeq(String conversationId); // helper to peek seq
}

interface INotificationService {
    void notifyUser(String userId, Message message);
    void shutdown();
}

// ===================== IN-MEMORY IMPLEMENTATIONS =====================

class InMemoryChatRepository implements IChatRepository {
    private final ConcurrentHashMap<String, User> users = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Conversation> conversations = new ConcurrentHashMap<>();
    // conversationId -> ordered list of messages
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Message>> messageStore = new ConcurrentHashMap<>();

    @Override
    public void saveMessage(Message message) {
        messageStore.computeIfAbsent(message.getConversationId(), cid -> new CopyOnWriteArrayList<>()).add(message);
    }

    @Override
    public List<Message> getHistory(String conversationId, int limit, int offset) {
        List<Message> msgs = messageStore.getOrDefault(conversationId, new CopyOnWriteArrayList<>());
        if (offset >= msgs.size()) return Collections.emptyList();
        int toIndex = Math.min(msgs.size(), offset + limit);
        return msgs.subList(offset, toIndex);
    }

    @Override
    public Conversation getConversation(String conversationId) {
        return conversations.get(conversationId);
    }

    @Override
    public void createConversation(Conversation conversation) {
        conversations.put(conversation.getId(), conversation);
        messageStore.putIfAbsent(conversation.getId(), new CopyOnWriteArrayList<>());
    }

    @Override
    public User getUser(String userId) {
        return users.get(userId);
    }

    @Override
    public void saveUser(User user) {
        users.put(user.getId(), user);
    }

    @Override
    public long getConversationSeq(String conversationId) {
        Conversation c = conversations.get(conversationId);
        return c == null ? 0L : c.seqGenerator.get();
    }
}

class ExecutorNotificationService implements INotificationService {
    private final ExecutorService executor;
    // simulate device mapping: userId -> device token or session; here we just print
    public ExecutorNotificationService(int threads) {
        this.executor = Executors.newFixedThreadPool(Math.max(1, threads));
    }

    @Override
    public void notifyUser(String userId, Message message) {
        executor.submit(() -> {
            // simulate push (in real system: WebSocket send / push gateway / FCM)
            System.out.printf("[PUSH] to %s: conversation=%s seq=%d msg=%s\n",
                    userId, message.getConversationId(), message.getSeqNo(), message.getContent());
            // Simulate update: in real system, delivery ack is received separately
        });
    }

    @Override
    public void shutdown() {
        executor.shutdownNow();
    }
}

// ===================== BUSINESS LOGIC =====================

class ChatService {
    private final IChatRepository repository;
    private final INotificationService notificationService;
    private final AtomicInteger messageIdGen = new AtomicInteger(0);
    private final AtomicInteger conversationIdGen = new AtomicInteger(1000);

    public ChatService(IChatRepository repo, INotificationService notify) {
        this.repository = repo;
        this.notificationService = notify;
    }

    // USER MANAGEMENT
    public User registerUser(String name) {
        String userId = "u-" + UUID.randomUUID().toString().substring(0, 8);
        User u = new User(userId, name);
        repository.saveUser(u);
        return u;
    }

    public void setStatus(String userId, UserStatus status) throws Exception {
        User u = repository.getUser(userId);
        if (u == null) throw new Exception("User not found");
        u.setStatus(status);
        repository.saveUser(u);
    }

    // CONVERSATIONS
    public String createPrivateChat(String u1, String u2) throws Exception {
        if (repository.getUser(u1) == null || repository.getUser(u2) == null) throw new Exception("User(s) not found");
        String cid = "conv-" + conversationIdGen.incrementAndGet();
        Conversation c = new PrivateChat(cid, u1, u2);
        repository.createConversation(c);
        return cid;
    }

    public String createGroupChat(String groupName, List<String> userIds) throws Exception {
        for (String uid : userIds) if (repository.getUser(uid) == null) throw new Exception("User not found: " + uid);
        String cid = "group-" + conversationIdGen.incrementAndGet();
        Conversation g = new GroupChat(cid, groupName, userIds);
        repository.createConversation(g);
        return cid;
    }

    // Send message (synchronous API; notification is async)
    public Message sendMessage(String senderId, String conversationId, String content) throws Exception {
        Conversation conv = repository.getConversation(conversationId);
        if (conv == null) throw new Exception("Conversation not found");
        if (!conv.getParticipants().contains(senderId)) throw new Exception("Sender not in conversation");

        long seq = conv.nextSequence(); // ordering guarantee per conversation
        String messageId = "m-" + messageIdGen.incrementAndGet();
        Message msg = new Message(messageId, senderId, conversationId, content, seq, conv.getParticipants());

        // persist first
        repository.saveMessage(msg);

        // fan-out notifications
        for (String participant : conv.getParticipants()) {
            if (participant.equals(senderId)) continue;
            // mark delivered optimistically or on ack — here we optimistically set DELIVERED then let client confirm READ later
            msg.setStatusFor(participant, MessageStatus.DELIVERED);
            notificationService.notifyUser(participant, msg);
        }

        return msg;
    }

    // Get chat history (cursor-based/pagination simplified)
    public List<Message> getChatHistory(String conversationId, int limit, int offset) {
        return repository.getHistory(conversationId, limit, offset);
    }

    // Mark messages as READ up to a sequence number for a particular user.
    // Efficient approach for groups: store "read watermark" per user per conversation (not shown in persistence)
    public void markReadUpTo(String userId, String conversationId, long seqNo) throws Exception {
        Conversation conv = repository.getConversation(conversationId);
        if (conv == null) throw new Exception("Conversation not found");
        if (!conv.getParticipants().contains(userId)) throw new Exception("User not in conversation");

        // iterate messages and set per-recipient status to READ for <= seqNo
        List<Message> msgs = repository.getHistory(conversationId, Integer.MAX_VALUE, 0);
        for (Message m : msgs) {
            if (m.getSeqNo() <= seqNo) {
                // only update if this user is a recipient
                m.setStatusFor(userId, MessageStatus.READ);
            }
        }
        // In real system, propagate read update to senders (via pub/sub) and persist watermarks
    }

    // Retrieve per-recipient status for a message (for debugging / UI)
    public Map<String, MessageStatus> getMessageStatus(String conversationId, String messageId) {
        List<Message> msgs = repository.getHistory(conversationId, Integer.MAX_VALUE, 0);
        for (Message m : msgs) if (m.getId().equals(messageId)) return m.getPerRecipientStatuses();
        return Collections.emptyMap();
    }
}

// ===================== DRIVER (PUBLIC CLASS) =====================

public class RealTimeChatSystem {
    public static void main(String[] args) {
        IChatRepository repo = new InMemoryChatRepository();
        ExecutorNotificationService push = new ExecutorNotificationService(4);
        ChatService chat = new ChatService(repo, push);

        try {
            // Register users
            User alice = chat.registerUser("Alice");
            User bob = chat.registerUser("Bob");
            User carol = chat.registerUser("Carol");

            // create chats
            String pv = chat.createPrivateChat(alice.getId(), bob.getId());
            String group = chat.createGroupChat("Weekend", Arrays.asList(alice.getId(), bob.getId(), carol.getId()));

            // send messages
            Message m1 = chat.sendMessage(alice.getId(), pv, "Hi Bob!");
            Message m2 = chat.sendMessage(bob.getId(), pv, "Hey Alice, all good.");

            Message g1 = chat.sendMessage(carol.getId(), group, "When's the meetup?");
            Message g2 = chat.sendMessage(alice.getId(), group, "Saturday 7pm.");

            // simulate read receipts
            chat.markReadUpTo(bob.getId(), pv, m1.getSeqNo()); // Bob read m1
            chat.markReadUpTo(alice.getId(), pv, m2.getSeqNo()); // Alice read m2

            // fetch history
            System.out.println("\nPrivate chat history:");
            chat.getChatHistory(pv, 50, 0).forEach(System.out::println);

            System.out.println("\nGroup chat history:");
            chat.getChatHistory(group, 50, 0).forEach(System.out::println);

            // check statuses
            System.out.println("\nMessage statuses for " + m1.getId() + ": " + chat.getMessageStatus(pv, m1.getId()));

        } catch (Exception ex) {
            ex.printStackTrace();
        } finally {
            push.shutdown();
        }
    }
}
