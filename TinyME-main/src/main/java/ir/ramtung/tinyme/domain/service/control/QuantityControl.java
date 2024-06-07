package ir.ramtung.tinyme.domain.service.control;

import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import org.springframework.stereotype.Component;

@Component
public class QuantityControl implements MatchingControl {
    private void removeOrdersWithZeroQuantity(Order order, OrderBook orderBook) {
        if (order.getQuantity() != 0) return;
        orderBook.removeByOrderId(order.getSide(), order.getOrderId());

        if (order instanceof IcebergOrder iOrder){
            iOrder.replenish();
            if (iOrder.getQuantity() > 0){
                orderBook.enqueue(iOrder);
            }
        }
    }
    @Override
    public void tradeQuantityUpdated(Order newOrder, Order matchingOrder, MatchingState mode) {
        OrderBook orderBook = newOrder.getSecurity().getOrderBook();
        int tradedQuantity = Math.min(newOrder.getQuantity(), matchingOrder.getQuantity());
        matchingOrder.decreaseQuantity(tradedQuantity);
        newOrder.decreaseQuantity(tradedQuantity);

        if (newOrder.getQuantity() < matchingOrder.getQuantity() && mode == MatchingState.CONTINUOUS) {
            newOrder.makeQuantityZero();
            return;
        }

        removeOrdersWithZeroQuantity(matchingOrder, orderBook);
        if (mode == MatchingState.AUCTION)
            removeOrdersWithZeroQuantity(newOrder, orderBook);

    }
}