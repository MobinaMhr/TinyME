package ir.ramtung.tinyme.domain.entity;

import lombok.Getter;

import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;

@Getter
public class InactiveOrderBook extends OrderBook{
    private final LinkedList<StopLimitOrder> inactiveSellOrderQueue;
    private final LinkedList<StopLimitOrder> inactiveBuyOrderQueue;

    public InactiveOrderBook() {
        inactiveSellOrderQueue = new LinkedList<>();
        inactiveBuyOrderQueue = new LinkedList<>();
    }
    public void DeActive(Order order) {
        assert order instanceof StopLimitOrder;
        enqueue(order);
    }
    @Override
    public void enqueue(Order order) {
        if (order instanceof StopLimitOrder stopLimitOrder) {
            var it = getInactiveQueue(stopLimitOrder.getSide()).listIterator();
            while (it.hasNext()) {
                if (stopLimitOrder.queuesBefore(it.next())) {
                    it.previous();
                    break;
                }
            }
            it.add(stopLimitOrder);
        }
    }

    private LinkedList<StopLimitOrder> getInactiveQueue(Side side) {
        return side == Side.BUY ? inactiveBuyOrderQueue : inactiveSellOrderQueue;
    }

    @Override
    public Order findByOrderId(Side side, long orderId) {
        for (Order order : getInactiveQueue(side)) {
            if (order.getOrderId() == orderId) {
                return order;
            }
        }
        return null;
    }

    @Override
    public boolean removeByOrderId(Side side, long orderId) {
        var inactiveIt = getInactiveQueue(side).listIterator();
        while (inactiveIt.hasNext()) {
            if (inactiveIt.next().getOrderId() == orderId) {
                inactiveIt.remove();
                return true;
            }
        }
        return false;
    }
    private StopLimitOrder findEligibleOrder(LinkedList<StopLimitOrder> orderQueue,
                                             int price) {
        if (orderQueue.isEmpty()) {
            return null;
        }

        StopLimitOrder stopLimitOrder = orderQueue.getFirst();
        if (!stopLimitOrder.canMeetLastTradePrice(price)) {
            return null;
        }

        orderQueue.removeFirst();
        return stopLimitOrder;
    }

    public StopLimitOrder getActivationCandidateOrder(int lastTradePrice) {
        StopLimitOrder stopLimitOrder;
        stopLimitOrder= findEligibleOrder(inactiveSellOrderQueue, lastTradePrice);
        if (stopLimitOrder != null) {
            return stopLimitOrder;
        }

        stopLimitOrder = findEligibleOrder(inactiveBuyOrderQueue, lastTradePrice);
        if (stopLimitOrder != null) {
            stopLimitOrder.getBroker().increaseCreditBy(stopLimitOrder.getValue());
        }
        return stopLimitOrder;
    }
}
