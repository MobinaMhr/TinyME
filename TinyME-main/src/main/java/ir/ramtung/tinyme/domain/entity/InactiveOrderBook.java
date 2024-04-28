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
            List<StopLimitOrder> queue = getInactiveQueue(stopLimitOrder.getSide());
            ListIterator<StopLimitOrder> it = queue.listIterator();
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
        var queue = getInactiveQueue(side);
        for (Order order : queue) {
            if (order.getOrderId() == orderId)
                return order;
        }
        return null;
    }

    @Override
    public boolean removeByOrderId(Side side, long orderId) {
        LinkedList<StopLimitOrder> inactiveQueue = getInactiveQueue(side);
        var inactiveIt = inactiveQueue.listIterator();
        while (inactiveIt.hasNext()) {
            if (inactiveIt.next().getOrderId() == orderId) {
                inactiveIt.remove();
                return true;
            }
        }
        return false;
    }

    public StopLimitOrder getActivateCandidateOrders(int lastTradePrice){
        if (!inactiveSellOrderQueue.isEmpty()) {
            StopLimitOrder stopLimitOrder = inactiveSellOrderQueue.getFirst();
            if (stopLimitOrder.canMeetLastTradePrice(lastTradePrice)) {
                inactiveSellOrderQueue.removeFirst();
                return stopLimitOrder;
            }
            return null;
        }
        if(!inactiveBuyOrderQueue.isEmpty()) {
            StopLimitOrder stopLimitOrder = inactiveBuyOrderQueue.getFirst();
            if (stopLimitOrder.canMeetLastTradePrice(lastTradePrice)) {
                inactiveBuyOrderQueue.removeFirst();
                stopLimitOrder.getBroker().increaseCreditBy(stopLimitOrder.getValue());
                return stopLimitOrder;
            }
            return null;
        }
        return null;
    }
}
