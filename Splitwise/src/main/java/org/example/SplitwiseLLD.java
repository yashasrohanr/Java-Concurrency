package org.example;

import java.util.*;

// =======================================================================
// 1. MODELS
// =======================================================================

class User {
    private final String id;
    private final String name;

    public User(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public String getId() { return id; }
    public String getName() { return name; }
}

class Group {
    private final String id;
    private final String name;
    private final Set<String> members;

    public Group(String id, String name, List<String> memberIds) {
        this.id = id;
        this.name = name;
        this.members = new HashSet<>(memberIds);
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public Set<String> getMembers() { return members; }
    public boolean hasMember(String userId) { return members.contains(userId); }
}

enum ExpenseType {
    EQUAL, EXACT, PERCENT
}

abstract class Split {
    protected User user;
    protected double amount;

    public Split(User user) { this.user = user; }

    public User getUser() { return user; }
    public double getAmount() { return amount; }
    public void setAmount(double amount) { this.amount = amount; }
}

class EqualSplit extends Split {
    public EqualSplit(User user) { super(user); }
}

class ExactSplit extends Split {
    public ExactSplit(User user, double amount) { super(user); this.amount = amount; }
}

class PercentSplit extends Split {
    private double percent;

    public PercentSplit(User user, double percent) {
        super(user);
        this.percent = percent;
    }

    public double getPercent() { return percent; }
}

class Expense {
    private final String id;
    private final double amount;
    private final String groupId;
    private final User paidBy;
    private final List<Split> splits;
    private final ExpenseType type;

    public Expense(String id, double amount, String groupId, User paidBy,
                   List<Split> splits, ExpenseType type) {
        this.id = id;
        this.amount = amount;
        this.groupId = groupId;
        this.paidBy = paidBy;
        this.splits = splits;
        this.type = type;
    }

    public User getPaidBy() { return paidBy; }
    public List<Split> getSplits() { return splits; }
    public String getGroupId() { return groupId; }
}

// =======================================================================
// 2. EXPENSE VALIDATION + FACTORY
// =======================================================================

abstract class ExpenseValidator {
    public Expense create(double amount, String groupId, User paidBy,
                          List<Split> splits, ExpenseType type) {
        validate(amount, splits);
        return new Expense(UUID.randomUUID().toString(), amount, groupId, paidBy, splits, type);
    }
    abstract void validate(double amount, List<Split> splits);
}

class ExactValidator extends ExpenseValidator {
    @Override
    void validate(double amount, List<Split> splits) {
        double sum = 0;
        for (Split s : splits) {
            if (!(s instanceof ExactSplit)) throw new RuntimeException("Invalid split");
            sum += s.getAmount();
        }
        if (Math.abs(sum - amount) > 0.01) throw new RuntimeException("Exact does not match");
    }
}

class PercentValidator extends ExpenseValidator {
    @Override
    void validate(double amount, List<Split> splits) {
        double sum = 0;
        for (Split s : splits) {
            if (!(s instanceof PercentSplit)) throw new RuntimeException("Invalid split");
            PercentSplit ps = (PercentSplit) s;
            sum += ps.getPercent();
            s.setAmount(amount * ps.getPercent() / 100.0);
        }
        if (Math.abs(sum - 100.0) > 0.01) throw new RuntimeException("Percent must be 100");
    }
}

class EqualValidator extends ExpenseValidator {
    @Override
    void validate(double amount, List<Split> splits) {
        double each = amount / splits.size();
        for (Split s : splits) {
            if (!(s instanceof EqualSplit)) throw new RuntimeException("Invalid split");
            s.setAmount(each);
        }
    }
}

class ExpenseFactory {
    public static Expense create(ExpenseType type, double amount, String groupId,
                                 User paidBy, List<Split> splits) {
        return switch (type) {
            case EXACT -> new ExactValidator().create(amount, groupId, paidBy, splits, type);
            case PERCENT -> new PercentValidator().create(amount, groupId, paidBy, splits, type);
            case EQUAL -> new EqualValidator().create(amount, groupId, paidBy, splits, type);
        };
    }
}

// =======================================================================
// 3. REPOSITORY LAYER (Persistence)
// =======================================================================

// ------------------ User Repo ------------------
interface UserRepository {
    void save(User user);
    User find(String id);
}

class InMemoryUserRepo implements UserRepository {
    private final Map<String, User> map = new HashMap<>();
    public void save(User user) { map.put(user.getId(), user); }
    public User find(String id) { return map.get(id); }
}

// ------------------ Group Repo ------------------
interface GroupRepository {
    void save(Group group);
    Group find(String id);
}

class InMemoryGroupRepo implements GroupRepository {
    private final Map<String, Group> map = new HashMap<>();
    public void save(Group g) { map.put(g.getId(), g); }
    public Group find(String id) { return map.get(id); }
}

// ------------------ Expense Repo ------------------
interface ExpenseRepository {
    void save(Expense exp);
    List<Expense> findByGroup(String groupId);
}

class InMemoryExpenseRepo implements ExpenseRepository {
    private final List<Expense> list = new ArrayList<>();
    public void save(Expense e) { list.add(e); }
    public List<Expense> findByGroup(String groupId) {
        List<Expense> result = new ArrayList<>();
        for (Expense e : list) {
            if (e.getGroupId().equals(groupId)) result.add(e);
        }
        return result;
    }
}

// =======================================================================
// 4. BALANCE SHEET + BASIC STRATEGY
// =======================================================================

class Transaction {
    String from, to;
    double amount;

    public Transaction(String from, String to, double amount) {
        this.from = from; this.to = to; this.amount = amount;
    }

    @Override
    public String toString() {
        return from + " pays " + to + " : " + String.format("%.2f", amount);
    }
}

interface SettlementStrategy {
    List<Transaction> settle(Map<String, Map<String, Double>> sheet);
}

class BasicStrategy implements SettlementStrategy {
    @Override
    public List<Transaction> settle(Map<String, Map<String, Double>> sheet) {
        List<Transaction> list = new ArrayList<>();
        for (var debtor : sheet.entrySet()) {
            for (var cred : debtor.getValue().entrySet()) {
                if (cred.getValue() > 0)
                    list.add(new Transaction(debtor.getKey(), cred.getKey(), cred.getValue()));
            }
        }
        return list;
    }
}

// =======================================================================
// 5. EXPENSE MANAGER (Validates Groups + Updates Balance Sheet)
// =======================================================================

class ExpenseManager {
    private final UserRepository userRepo;
    private final GroupRepository groupRepo;
    private final ExpenseRepository expRepo;

    private final SettlementStrategy strategy = new BasicStrategy();

    // balanceSheet: user1 -> (user2 -> amount user1 owes user2)
    private final Map<String, Map<String, Double>> balanceSheet = new HashMap<>();

    public ExpenseManager(UserRepository u, GroupRepository g, ExpenseRepository e) {
        this.userRepo = u; this.groupRepo = g; this.expRepo = e;
    }

    // -------- Add Expense --------
    public void addExpense(ExpenseType type, double amount, String groupId,
                           String paidByUserId, List<Split> splits) {

        Group group = groupRepo.find(groupId);
        if (group == null) throw new RuntimeException("Group not found");

        // Validate group membership
        if (!group.hasMember(paidByUserId))
            throw new RuntimeException("Payer not in group");

        for (Split s : splits) {
            if (!group.hasMember(s.getUser().getId()))
                throw new RuntimeException("Split user not in group: " + s.getUser().getId());
        }

        User paidBy = userRepo.find(paidByUserId);

        Expense exp = ExpenseFactory.create(type, amount, groupId, paidBy, splits);
        expRepo.save(exp);

        updateBalances(exp);
    }

    private void updateBalances(Expense exp) {
        String paidBy = exp.getPaidBy().getId();

        for (Split s : exp.getSplits()) {
            String user = s.getUser().getId();
            double amount = s.getAmount();
            if (user.equals(paidBy)) continue;
            addDebt(user, paidBy, amount);
        }
    }

    private void addDebt(String debtor, String creditor, double amount) {
        balanceSheet.putIfAbsent(debtor, new HashMap<>());
        balanceSheet.putIfAbsent(creditor, new HashMap<>());

        double reverse = balanceSheet.get(creditor).getOrDefault(debtor, 0.0);

        if (reverse > 0) {
            if (amount > reverse) {
                balanceSheet.get(creditor).remove(debtor);
                balanceSheet.get(debtor).put(creditor, amount - reverse);
            } else {
                balanceSheet.get(creditor).put(debtor, reverse - amount);
                if (reverse - amount == 0) balanceSheet.get(creditor).remove(debtor);
            }
        } else {
            balanceSheet.get(debtor).put(creditor,
                    balanceSheet.get(debtor).getOrDefault(creditor, 0.0) + amount);
        }
    }

    public void showBalances(String userId) {
        System.out.println("Balances for " + userId + ":");

        boolean empty = true;

        if (balanceSheet.containsKey(userId)) {
            for (var entry : balanceSheet.get(userId).entrySet()) {
                System.out.println("Owes " + entry.getKey() + " : " + entry.getValue());
                empty = false;
            }
        }

        for (var debtor : balanceSheet.entrySet()) {
            if (debtor.getValue().containsKey(userId)) {
                System.out.println(debtor.getKey() + " owes you : " + debtor.getValue().get(userId));
                empty = false;
            }
        }

        if (empty) System.out.println("No Balance");
    }

    public void showSettlement() {
        System.out.println("\n--- Transactions ---");
        for (Transaction t : strategy.settle(balanceSheet)) {
            System.out.println(t);
        }
    }
}

// =======================================================================
// 6. MAIN
// =======================================================================

public class SplitwiseLLD {
    public static void main(String[] args) {

        UserRepository ur = new InMemoryUserRepo();
        GroupRepository gr = new InMemoryGroupRepo();
        ExpenseRepository er = new InMemoryExpenseRepo();

        ExpenseManager manager = new ExpenseManager(ur, gr, er);

        // USERS
        User u1 = new User("U1", "Alice");
        User u2 = new User("U2", "Bob");
        User u3 = new User("U3", "Charlie");
        User u4 = new User("U4", "David");

        ur.save(u1); ur.save(u2); ur.save(u3); ur.save(u4);

        // GROUP
        Group g = new Group("G1", "Goa Trip", List.of("U1", "U2", "U3", "U4"));
        gr.save(g);

        // EXPENSE 1 (Equal)
        List<Split> s1 = List.of(
                new EqualSplit(u1),
                new EqualSplit(u2),
                new EqualSplit(u3),
                new EqualSplit(u4)
        );
        manager.addExpense(ExpenseType.EQUAL, 1000, "G1", "U1", s1);

        // EXPENSE 2 (Exact)
        List<Split> s2 = List.of(
                new ExactSplit(u2, 300),
                new ExactSplit(u3, 200)
        );
        manager.addExpense(ExpenseType.EXACT, 500, "G1", "U2", s2);

        // EXPENSE 3 (Percent)
        List<Split> s3 = List.of(
                new PercentSplit(u1, 40),
                new PercentSplit(u4, 60)
        );
        manager.addExpense(ExpenseType.PERCENT, 200, "G1", "U4", s3);

        // SHOW BALANCES
        System.out.println();
        manager.showBalances("U1");
        manager.showBalances("U2");
        manager.showBalances("U3");

        // FINAL SETTLEMENT
        manager.showSettlement();
    }
}
