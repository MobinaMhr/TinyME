package ir.ramtung.tinyme.domain.entity;

import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.time.LocalDateTime;

@Getter
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class StopLimitOrder extends Order {
    protected int stopPrice;

    public StopLimitOrder(long orderId, Security security, Side side, int quantity, int price, Broker broker, Shareholder shareholder, LocalDateTime entryTime, OrderStatus status, int stopPrice) {
        super(orderId, security, side, quantity, price, broker, shareholder, entryTime, status, 0);
        this.stopPrice = stopPrice;
    }

    public StopLimitOrder(long orderId, Security security, Side side, int quantity, int price, Broker broker, Shareholder shareholder, LocalDateTime entryTime, int stopPrice) {
        this(orderId, security, side, quantity, price, broker, shareholder, entryTime, OrderStatus.NEW, stopPrice);
    }

    public StopLimitOrder(long orderId, Security security, Side side, int quantity, int price, Broker broker, Shareholder shareholder, int stopPrice) {
        this(orderId, security, side, quantity, price, broker, shareholder, LocalDateTime.now(), OrderStatus.NEW, stopPrice);
    }

    @Override
    public Order snapshot() {
        return new StopLimitOrder(orderId, security, side, quantity, price, broker, shareholder, entryTime, OrderStatus.SNAPSHOT,
                stopPrice);
    }

    @Override
    public Order snapshotWithQuantity(int newQuantity) {
        return new StopLimitOrder(orderId, security, side, newQuantity, price, broker, shareholder, entryTime, OrderStatus.SNAPSHOT,
                stopPrice);
    }

    public boolean canMeetLastTradePrice(int lastTradePrice){
        return this.getSide() == Side.BUY && lastTradePrice >= this.getStopPrice() ||
                this.getSide() == Side.SELL && lastTradePrice <= this.getStopPrice();
    }

    @Override
    public void updateFromRequest(EnterOrderRq updateOrderRq) {
        super.updateFromRequest(updateOrderRq);
        stopPrice = updateOrderRq.getStopPrice();
    }

    @Override
    public boolean queuesBefore(Order order) {
        if (order instanceof StopLimitOrder stopLimitOrder) {
            if (stopLimitOrder.getSide() == Side.BUY) {
                return stopPrice < stopLimitOrder.getStopPrice();
            } else {
                return stopPrice > stopLimitOrder.getStopPrice();
            }
        }
        return false;
    }
}
