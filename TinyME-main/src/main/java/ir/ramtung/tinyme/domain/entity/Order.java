package ir.ramtung.tinyme.domain.entity;

import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.time.LocalDateTime;

@Builder
@EqualsAndHashCode
@ToString
@Getter
public class Order {
    protected long orderId;
    protected Security security;
    protected Side side;
    protected int quantity;
    protected int price;
    protected Broker broker;
    protected Shareholder shareholder;
    @Builder.Default
    protected LocalDateTime entryTime = LocalDateTime.now();
    @Builder.Default
    protected OrderStatus status = OrderStatus.NEW;
    protected int minimumExecutionQuantity;
    protected int initialQuantity;


    public Order(long orderId, Security security, Side side, int quantity, int price,
                 Broker broker, Shareholder shareholder, LocalDateTime entryTime,
                 OrderStatus status, int minimumExecutionQuantity, int initialQuantity) {
        this.orderId = orderId;
        this.security = security;
        this.side = side;
        this.quantity = quantity;
        this.price = price;
        this.entryTime = entryTime;
        this.broker = broker;
        this.shareholder = shareholder;
        this.status = status;
        this.minimumExecutionQuantity = minimumExecutionQuantity;
        this.initialQuantity = initialQuantity;
    }

    public Order(long orderId, Security security, Side side, int quantity, int price,
                 Broker broker, Shareholder shareholder, LocalDateTime entryTime,
                 OrderStatus status, int minimumExecutionQuantity) {
        this(orderId, security, side, quantity, price,
                broker, shareholder, entryTime, status, minimumExecutionQuantity, quantity);

    }
    public Order(long orderId, Security security, Side side, int quantity, int price,
                 Broker broker, Shareholder shareholder, LocalDateTime entryTime,
                 OrderStatus status) {
        this(orderId, security, side, quantity, price, broker,
                shareholder, entryTime, status, 0
        );
    }

    public Order(long orderId, Security security, Side side, int quantity, int price,
                 Broker broker, Shareholder shareholder, LocalDateTime entryTime) {
        this(orderId, security, side, quantity, price, broker,
                shareholder, entryTime, OrderStatus.NEW
        );
    }

    public Order(long orderId, Security security, Side side, int quantity, int price,
                 Broker broker, Shareholder shareholder) {
        this(orderId, security, side, quantity, price, broker,
                shareholder, LocalDateTime.now()
        );
    }

    public Order(StopLimitOrder stopLimitOrder){
        this(stopLimitOrder.orderId, stopLimitOrder.security,
                stopLimitOrder.side, stopLimitOrder.quantity,
                stopLimitOrder.price, stopLimitOrder.broker,
                stopLimitOrder.shareholder, stopLimitOrder.entryTime
        );
    }

    public Order snapshot() {
        return snapshotWithQuantity(quantity);
    }

    public Order snapshotWithQuantity(int newQuantity) {
        return new Order(orderId, security, side, newQuantity,
                price, broker, shareholder, entryTime,
                OrderStatus.SNAPSHOT, minimumExecutionQuantity, initialQuantity);
    }

    public boolean minimumExecutionQuantitySatisfied() {
        if (initialQuantity - quantity < minimumExecutionQuantity)
            return false;
        return true;
    }

    public boolean matches(Order other) {
        if (side == Side.BUY)
            return price >= other.price;
        else
            return price <= other.price;
    }

    public void decreaseQuantity(int amount) {
        if (amount > quantity)
            throw new IllegalArgumentException();
        quantity -= amount;
    }

    public void makeQuantityZero() {
        quantity = 0;
    }

    public boolean queuesBefore(Order order) {
        if (order.getSide() == Side.BUY) {
            return price > order.getPrice();
        } else {
            return price < order.getPrice();
        }
    }

    public boolean minimumExecutionQuantitySatisfied(int initialQuantity) {
        return initialQuantity - quantity >= minimumExecutionQuantity;
    }

    public void queue() {
        status = OrderStatus.QUEUED;
    }

    public boolean isQuantityIncreased(int newQuantity) {
        return newQuantity > quantity;
    }

    public void updateFromRequest(EnterOrderRq updateOrderRq) {
        if (quantity != updateOrderRq.getQuantity()) {
            int executedQuantity = initialQuantity - quantity;
            initialQuantity = updateOrderRq.getQuantity() + executedQuantity;
        }
        quantity = updateOrderRq.getQuantity();
        price = updateOrderRq.getPrice();
    }

    public long getValue() {
        return (long)price * quantity;
    }

    public int getTotalQuantity() { return quantity; }
}
