package ir.ramtung.tinyme.domain.service.control;

import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.control.MatchingControl;
import org.springframework.stereotype.Component;

@Component
public class QuantityControl implements MatchingControl {
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
        if (matchingOrder instanceof IcebergOrder icebergOrder) {
            icebergOrder.decreaseQuantity(matchingOrder.getQuantity());
            icebergOrder.replenish();
            if (icebergOrder.getQuantity() > 0) orderBook.enqueue(icebergOrder);
        }
    }
}
