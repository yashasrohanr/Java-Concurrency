package org.example;

// ==================== DOMAIN ENTITIES ====================

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

// Product Entity
class Product {
    private final String id;
    private final String name;
    private final BigDecimal price;
    private final String category;

    public Product(String id, String name, BigDecimal price, String category) {
        this.id = id;
        this.name = name;
        this.price = price;
        this.category = category;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public BigDecimal getPrice() { return price; }
    public String getCategory() { return category; }
}

// CartItem Entity
class CartItem {
    private final Product product;
    private int quantity;

    public CartItem(Product product, int quantity) {
        this.product = product;
        this.quantity = quantity;
    }

    public Product getProduct() { return product; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }

    public BigDecimal getSubtotal() {
        return product.getPrice().multiply(BigDecimal.valueOf(quantity));
    }
}

// User Entity
class User {
    private final String id;
    private final String name;
    private final String email;

    public User(String id, String name, String email) {
        this.id = id;
        this.name = name;
        this.email = email;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getEmail() { return email; }
}

// ==================== COUPON STRATEGY PATTERN ====================

// Strategy Interface
interface CouponStrategy {
    DiscountResult apply(Cart cart);
    boolean isApplicable(Cart cart);
    String getCouponId();
}

// Discount Result DTO
class DiscountResult {
    private final BigDecimal discountAmount;
    private final String couponId;
    private final String description;
    private final Map<String, BigDecimal> itemWiseDiscount; // For item-level tracking

    public DiscountResult(BigDecimal discountAmount, String couponId, String description) {
        this.discountAmount = discountAmount;
        this.couponId = couponId;
        this.description = description;
        this.itemWiseDiscount = new HashMap<>();
    }

    public DiscountResult(BigDecimal discountAmount, String couponId, String description,
                          Map<String, BigDecimal> itemWiseDiscount) {
        this.discountAmount = discountAmount;
        this.couponId = couponId;
        this.description = description;
        this.itemWiseDiscount = itemWiseDiscount;
    }

    public BigDecimal getDiscountAmount() { return discountAmount; }
    public String getCouponId() { return couponId; }
    public String getDescription() { return description; }
    public Map<String, BigDecimal> getItemWiseDiscount() { return itemWiseDiscount; }
}

// Abstract Base Coupon
abstract class BaseCoupon implements CouponStrategy {
    protected final String couponId;
    protected final LocalDateTime expiryDate;
    protected final Integer usageLimit;
    protected final BigDecimal minCartValue;
    protected final Set<String> excludedProducts;

    public BaseCoupon(String couponId, LocalDateTime expiryDate, Integer usageLimit,
                      BigDecimal minCartValue,
                      Set<String> excludedProducts) {
        this.couponId = couponId;
        this.expiryDate = expiryDate;
        this.usageLimit = usageLimit;
        this.minCartValue = minCartValue;
        this.excludedProducts = excludedProducts;
    }

    @Override
    public String getCouponId() {
        return couponId;
    }

    @Override
    public boolean isApplicable(Cart cart) {
        // Check expiry
        if (expiryDate != null && LocalDateTime.now().isAfter(expiryDate)) {
            return false;
        }

        // Check usage limit (would need user context - simplified here)
        // In production: check from CouponUsageRepository

        // Check minimum cart value
        if (minCartValue != null && cart.getSubtotal().compareTo(minCartValue) < 0) {
            return false;
        }

        return true;
    }
}

// ==================== ITEM-LEVEL COUPONS ====================

// Percentage discount on specific product
class ProductPercentageCoupon extends BaseCoupon {
    private final String productId;
    private final BigDecimal discountPercentage;

    public ProductPercentageCoupon(String couponId, String productId,
                                   BigDecimal discountPercentage, LocalDateTime expiryDate) {
        super(couponId, expiryDate, null, null, null);
        this.productId = productId;
        this.discountPercentage = discountPercentage;
    }

    @Override
    public DiscountResult apply(Cart cart) {
        BigDecimal totalDiscount = BigDecimal.ZERO;
        Map<String, BigDecimal> itemWiseDiscount = new HashMap<>();

        for (CartItem item : cart.getItems()) {
            if (item.getProduct().getId().equals(productId)) {
                BigDecimal discount = item.getSubtotal()
                        .multiply(discountPercentage)
                        .divide(BigDecimal.valueOf(100));
                totalDiscount = totalDiscount.add(discount);
                itemWiseDiscount.put(productId, discount);
            }
        }

        return new DiscountResult(totalDiscount, couponId,
                discountPercentage + "% off on product " + productId, itemWiseDiscount);
    }

    @Override
    public boolean isApplicable(Cart cart) {
        if (!super.isApplicable(cart)) return false;
        return cart.getItems().stream()
                .anyMatch(item -> item.getProduct().getId().equals(productId));
    }
}

// Flat discount on specific product
class ProductFlatCoupon extends BaseCoupon {
    private final String productId;
    private final BigDecimal flatDiscount;

    public ProductFlatCoupon(String couponId, String productId,
                             BigDecimal flatDiscount, LocalDateTime expiryDate) {
        super(couponId, expiryDate, null, null, null);
        this.productId = productId;
        this.flatDiscount = flatDiscount;
    }

    @Override
    public DiscountResult apply(Cart cart) {
        BigDecimal totalDiscount = BigDecimal.ZERO;
        Map<String, BigDecimal> itemWiseDiscount = new HashMap<>();

        for (CartItem item : cart.getItems()) {
            if (item.getProduct().getId().equals(productId)) {
                BigDecimal discount = flatDiscount.min(item.getSubtotal());
                totalDiscount = totalDiscount.add(discount);
                itemWiseDiscount.put(productId, discount);
            }
        }

        return new DiscountResult(totalDiscount, couponId,
                "₹" + flatDiscount + " off on product " + productId, itemWiseDiscount);
    }

    @Override
    public boolean isApplicable(Cart cart) {
        if (!super.isApplicable(cart)) return false;
        return cart.getItems().stream()
                .anyMatch(item -> item.getProduct().getId().equals(productId));
    }
}

// BOGO (Buy X Get Y Free)
class BOGOCoupon extends BaseCoupon {
    private final String productId;
    private final int buyQuantity;
    private final int freeQuantity;

    public BOGOCoupon(String couponId, String productId, int buyQuantity,
                      int freeQuantity, LocalDateTime expiryDate) {
        super(couponId, expiryDate, null, null, null);
        this.productId = productId;
        this.buyQuantity = buyQuantity;
        this.freeQuantity = freeQuantity;
    }

    @Override
    public DiscountResult apply(Cart cart) {
        for (CartItem item : cart.getItems()) {
            if (item.getProduct().getId().equals(productId)) {
                int totalQuantity = item.getQuantity();
                int setsApplicable = totalQuantity / (buyQuantity + freeQuantity);
                int freeItems = setsApplicable * freeQuantity;

                BigDecimal discount = item.getProduct().getPrice()
                        .multiply(BigDecimal.valueOf(freeItems));

                Map<String, BigDecimal> itemWiseDiscount = new HashMap<>();
                itemWiseDiscount.put(productId, discount);

                return new DiscountResult(discount, couponId,
                        "Buy " + buyQuantity + " Get " + freeQuantity + " Free",
                        itemWiseDiscount);
            }
        }
        return new DiscountResult(BigDecimal.ZERO, couponId, "BOGO not applicable");
    }

    @Override
    public boolean isApplicable(Cart cart) {
        if (!super.isApplicable(cart)) return false;
        return cart.getItems().stream()
                .anyMatch(item -> item.getProduct().getId().equals(productId)
                        && item.getQuantity() >= buyQuantity);
    }
}

// ==================== CART-LEVEL COUPONS ====================

// Percentage discount on entire cart
class CartPercentageCoupon extends BaseCoupon {
    private final BigDecimal discountPercentage;

    public CartPercentageCoupon(String couponId, BigDecimal discountPercentage,
                                BigDecimal minCartValue, LocalDateTime expiryDate) {
        super(couponId, expiryDate, null, minCartValue, null);
        this.discountPercentage = discountPercentage;
    }

    @Override
    public DiscountResult apply(Cart cart) {
        BigDecimal discount = cart.getSubtotal()
                .multiply(discountPercentage)
                .divide(BigDecimal.valueOf(100));

        return new DiscountResult(discount, couponId,
                discountPercentage + "% off on cart (min ₹" + minCartValue + ")");
    }
}

// Flat discount on entire cart
class CartFlatCoupon extends BaseCoupon {
    private final BigDecimal flatDiscount;

    public CartFlatCoupon(String couponId, BigDecimal flatDiscount,
                          BigDecimal minCartValue, LocalDateTime expiryDate) {
        super(couponId, expiryDate, null, minCartValue, null);
        this.flatDiscount = flatDiscount;
    }

    @Override
    public DiscountResult apply(Cart cart) {
        BigDecimal discount = flatDiscount.min(cart.getSubtotal());

        return new DiscountResult(discount, couponId,
                "₹" + flatDiscount + " off on cart (min ₹" + minCartValue + ")");
    }
}

// ==================== CART ENTITY ====================

class Cart {
    private final String cartId;
    private final User user;
    private final Map<String, CartItem> items; // productId -> CartItem
    private final List<CouponStrategy> appliedCoupons;
    private final ReentrantReadWriteLock lock;

    public Cart(String cartId, User user) {
        this.cartId = cartId;
        this.user = user;
        this.items = new ConcurrentHashMap<>();
        this.appliedCoupons = new ArrayList<>();
        this.lock = new ReentrantReadWriteLock();
    }

    // Thread-safe add item
    public void addItem(Product product, int quantity) {
        lock.writeLock().lock();
        try {
            items.compute(product.getId(), (k, existing) -> {
                if (existing == null) {
                    return new CartItem(product, quantity);
                } else {
                    existing.setQuantity(existing.getQuantity() + quantity);
                    return existing;
                }
            });
        } finally {
            lock.writeLock().unlock();
        }
    }

    // Thread-safe remove item
    public void removeItem(String productId) {
        lock.writeLock().lock();
        try {
            items.remove(productId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // Thread-safe update quantity
    public void updateQuantity(String productId, int quantity) {
        lock.writeLock().lock();
        try {
            CartItem item = items.get(productId);
            if (item != null) {
                if (quantity <= 0) {
                    items.remove(productId);
                } else {
                    item.setQuantity(quantity);
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public BigDecimal getSubtotal() {
        lock.readLock().lock();
        try {
            return items.values().stream()
                    .map(CartItem::getSubtotal)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        } finally {
            lock.readLock().unlock();
        }
    }

    public Collection<CartItem> getItems() {
        return items.values();
    }

    public int getTotalItemCount() {
        return items.values().stream()
                .mapToInt(CartItem::getQuantity)
                .sum();
    }

    public void addCoupon(CouponStrategy coupon) {
        appliedCoupons.add(coupon);
    }

    public void clearCoupons() {
        appliedCoupons.clear();
    }

    public List<CouponStrategy> getAppliedCoupons() {
        return new ArrayList<>(appliedCoupons);
    }

    public String getCartId() { return cartId; }
    public User getUser() { return user; }
}

// ==================== COUPON SELECTION STRATEGY ====================

interface CouponSelectionStrategy {
    List<DiscountResult> selectBestCoupons(Cart cart, List<CouponStrategy> availableCoupons);
}

// Select single best coupon (max discount)
class BestSingleCouponStrategy implements CouponSelectionStrategy {
    @Override
    public List<DiscountResult> selectBestCoupons(Cart cart, List<CouponStrategy> availableCoupons) {
        DiscountResult bestDiscount = null;

        for (CouponStrategy coupon : availableCoupons) {
            if (coupon.isApplicable(cart)) {
                DiscountResult result = coupon.apply(cart);
                if (bestDiscount == null ||
                        result.getDiscountAmount().compareTo(bestDiscount.getDiscountAmount()) > 0) {
                    bestDiscount = result;
                }
            }
        }

        return bestDiscount != null ? List.of(bestDiscount) : Collections.emptyList();
    }
}

// Stack multiple coupons
class StackableCouponStrategy implements CouponSelectionStrategy {
    @Override
    public List<DiscountResult> selectBestCoupons(Cart cart, List<CouponStrategy> availableCoupons) {
        List<DiscountResult> results = new ArrayList<>();

        // Apply item-level coupons first, then cart-level
        for (CouponStrategy coupon : availableCoupons) {
            if (coupon.isApplicable(cart)) {
                results.add(coupon.apply(cart));
            }
        }

        return results;
    }
}

// ==================== CART SERVICE ====================

class CartService {
    private final Map<String, Cart> cartRepository = new ConcurrentHashMap<>();
    private final CouponSelectionStrategy couponSelectionStrategy;

    public CartService(CouponSelectionStrategy couponSelectionStrategy) {
        this.couponSelectionStrategy = couponSelectionStrategy;
    }

    public Cart getOrCreateCart(User user) {
        return cartRepository.computeIfAbsent(user.getId(),
                k -> new Cart(UUID.randomUUID().toString(), user));
    }

    public void addItemToCart(String userId, Product product, int quantity) {
        Cart cart = cartRepository.get(userId);
        if (cart != null) {
            cart.addItem(product, quantity);
        }
    }

    public void removeItemFromCart(String userId, String productId) {
        Cart cart = cartRepository.get(userId);
        if (cart != null) {
            cart.removeItem(productId);
        }
    }

    public void updateItemQuantity(String userId, String productId, int quantity) {
        Cart cart = cartRepository.get(userId);
        if (cart != null) {
            cart.updateQuantity(productId, quantity);
        }
    }

    public CartSummary getCartSummary(String userId, List<CouponStrategy> availableCoupons) {
        Cart cart = cartRepository.get(userId);
        if (cart == null) {
            return null;
        }

        BigDecimal subtotal = cart.getSubtotal();
        List<DiscountResult> discounts = couponSelectionStrategy.selectBestCoupons(cart, availableCoupons);

        BigDecimal totalDiscount = discounts.stream()
                .map(DiscountResult::getDiscountAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal total = subtotal.subtract(totalDiscount);

        return new CartSummary(subtotal, totalDiscount, total, discounts);
    }
}

// ==================== DTOs ====================

class CartSummary {
    private final BigDecimal subtotal;
    private final BigDecimal totalDiscount;
    private final BigDecimal total;
    private final List<DiscountResult> appliedDiscounts;

    public CartSummary(BigDecimal subtotal, BigDecimal totalDiscount,
                       BigDecimal total, List<DiscountResult> appliedDiscounts) {
        this.subtotal = subtotal;
        this.totalDiscount = totalDiscount;
        this.total = total;
        this.appliedDiscounts = appliedDiscounts;
    }

    public BigDecimal getSubtotal() { return subtotal; }
    public BigDecimal getTotalDiscount() { return totalDiscount; }
    public BigDecimal getTotal() { return total; }
    public List<DiscountResult> getAppliedDiscounts() { return appliedDiscounts; }

    @Override
    public String toString() {
        return String.format("Subtotal: ₹%s, Discount: ₹%s, Total: ₹%s",
                subtotal, totalDiscount, total);
    }
}

// ==================== DEMO ====================

class ShoppingCartDemo {
    public static void main(String[] args) {
        // Create products
        Product laptop = new Product("P1", "Laptop", new BigDecimal("50000"), "Electronics");
        Product mouse = new Product("P2", "Mouse", new BigDecimal("500"), "Electronics");
        Product keyboard = new Product("P3", "Keyboard", new BigDecimal("1500"), "Electronics");

        // Create user
        User user = new User("U1", "John Doe", "john@example.com");

        // Create cart service with best single coupon strategy
        CartService cartService = new CartService(new StackableCouponStrategy());
        Cart cart = cartService.getOrCreateCart(user);

        // Add items
        cartService.addItemToCart(user.getId(), laptop, 1);
        cartService.addItemToCart(user.getId(), mouse, 2);
        cartService.addItemToCart(user.getId(), keyboard, 1);

        // Create coupons
        List<CouponStrategy> coupons = new ArrayList<>();
        coupons.add(new ProductPercentageCoupon("LAPTOP10", "P1",
                new BigDecimal("10"), LocalDateTime.now().plusDays(30)));
        coupons.add(new CartPercentageCoupon("CART5", new BigDecimal("5"),
                new BigDecimal("10000"), LocalDateTime.now().plusDays(30)));
        coupons.add(new CartFlatCoupon("FLAT1000", new BigDecimal("1000"),
                new BigDecimal("20000"), LocalDateTime.now().plusDays(30)));
        coupons.add(new BOGOCoupon("BOGO-MOUSE", "P2", 1, 1,
                LocalDateTime.now().plusDays(30)));

        // Get cart summary with best coupon
        CartSummary summary = cartService.getCartSummary(user.getId(), coupons);
        System.out.println(summary);

        for (DiscountResult discount : summary.getAppliedDiscounts()) {
            System.out.println("Applied: " + discount.getDescription() +
                    " - Saved ₹" + discount.getDiscountAmount());
        }
    }
}