package ir.ramtung.tinyme.domain.service.control;

import ir.ramtung.tinyme.domain.entity.*;
import org.springframework.stereotype.Component;

@Component
public class QuantityControl implements MatchingControl {
    private void removeOrdersWithZeroQuantity(Order order, Side side, OrderBook orderBook) {
        if (order.getQuantity() != 0) return;
        orderBook.removeByOrderId(side, order.getOrderId());

        if (order instanceof IcebergOrder iOrder){
            iOrder.replenish();
            if (iOrder.getQuantity() > 0){
                orderBook.enqueue(iOrder);
            }
        }
    }
    @Override
    public void tradeQuantityUpdated(Order newOrder, Order matchingOrder, Trade trade) {
        OrderBook orderBook = newOrder.getSecurity().getOrderBook();

        if (newOrder.getQuantity() < matchingOrder.getQuantity()) {
            matchingOrder.decreaseQuantity(newOrder.getQuantity());
            newOrder.makeQuantityZero();
            return;
        }
        newOrder.decreaseQuantity(matchingOrder.getQuantity());
        orderBook.removeFirst(matchingOrder.getSide());

//        OrderBook orderBook = newOrder.getSecurity().getOrderBook();
//        int tradedQuantity = Math.min(newOrder.getQuantity(), matchingOrder.getQuantity());
//        newOrder.decreaseQuantity(tradedQuantity);
//        matchingOrder.decreaseQuantity(tradedQuantity);
//
//        removeOrdersWithZeroQuantity(newOrder, Side.BUY, orderBook);
//        removeOrdersWithZeroQuantity(matchingOrder, Side.SELL, orderBook);

        if (matchingOrder instanceof IcebergOrder icebergOrder) {
            icebergOrder.decreaseQuantity(matchingOrder.getQuantity());
            icebergOrder.replenish();
            if (icebergOrder.getQuantity() > 0) orderBook.enqueue(icebergOrder);
        }
    }
}