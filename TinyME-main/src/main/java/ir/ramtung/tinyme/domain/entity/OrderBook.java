package ir.ramtung.tinyme.domain.entity;

import lombok.Getter;

import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;

@Getter
public class OrderBook {
    private final LinkedList<Order> buyQueue;
    private final LinkedList<Order> sellQueue;
    private final LinkedList<StopLimitOrder> inactiveSellOrderQueue;
    private final LinkedList<StopLimitOrder> inactiveBuyOrderQueue;

    public OrderBook() {
        buyQueue = new LinkedList<>();
        sellQueue = new LinkedList<>();
        inactiveSellOrderQueue = new LinkedList<>();
        inactiveBuyOrderQueue = new LinkedList<>();
    }
    public void DeActive(Order order) {
        assert order instanceof StopLimitOrder;
        enqueueInactiveOrder(order);
    }

    public void enqueueInactiveOrder(Order order) {
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

    public void enqueue(Order order) {
        List<Order> queue = getQueue(order.getSide());
        ListIterator<Order> it = queue.listIterator();
        while (it.hasNext()) {
            if (order.queuesBefore(it.next())) {
                it.previous();
                break;
            }
        }
        order.queue();
        it.add(order);
    }

    private LinkedList<Order> getQueue(Side side) {
        return side == Side.BUY ? buyQueue : sellQueue;
    }

    private LinkedList<StopLimitOrder> getInactiveQueue(Side side) {
        return side == Side.BUY ? inactiveBuyOrderQueue : inactiveSellOrderQueue;
    }

    public Order findByOrderId(Side side, long orderId) {
        var queue = getQueue(side);
        for (Order order : queue) {
            if (order.getOrderId() == orderId)
                return order;
        }
        return null;
    }

    public boolean removeByOrderId(Side side, long orderId) {
        var queue = getQueue(side);
        var it = queue.listIterator();
        while (it.hasNext()) {
            if (it.next().getOrderId() == orderId) {
                it.remove();
                return true;
            }
        }
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

    public Order matchWithFirst(Order newOrder) {
        var queue = getQueue(newOrder.getSide().opposite());
        if (newOrder.matches(queue.getFirst()))
            return queue.getFirst();
        else
            return null;
    }

    public void putBack(Order order) {
        LinkedList<Order> queue = getQueue(order.getSide());
        order.queue();
        queue.addFirst(order);
    }

    public void restoreOrder(Order order) {
        removeByOrderId(order.getSide(), order.getOrderId());
        putBack(order);
    }

    public boolean hasOrderOfType(Side side) {
        return !getQueue(side).isEmpty();
    }

    public void removeFirst(Side side) {
        getQueue(side).removeFirst();
    }

    public int totalSellQuantityByShareholder(Shareholder shareholder) {
        return sellQueue.stream()
                .filter(order -> order.getShareholder().equals(shareholder))
                .mapToInt(Order::getTotalQuantity)
                .sum();
    }

    public StopLimitOrder getActivateCandidateOrders(int lastTradePrice){
        StopLimitOrder stopLimitOrder = inactiveSellOrderQueue.getFirst();
        if (stopLimitOrder.canMeetLastTradePrice(lastTradePrice)){
            inactiveSellOrderQueue.removeFirst();
            return stopLimitOrder;
        }
        stopLimitOrder = inactiveBuyOrderQueue.getFirst();
        if (stopLimitOrder.canMeetLastTradePrice(lastTradePrice)){
            inactiveBuyOrderQueue.removeFirst();
            return stopLimitOrder;
        }
        else
            return null;
    }

    public Order findByOrderIdForInactiveQueue(Side side, long orderId) {
        var queue = getInactiveQueue(side);
        for (Order order : queue) {
            if (order.getOrderId() == orderId)
                return order;
        }
        return null;
    }
}
