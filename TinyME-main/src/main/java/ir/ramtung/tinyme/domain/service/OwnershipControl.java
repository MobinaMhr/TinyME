package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.Order;
import ir.ramtung.tinyme.domain.entity.Side;
import ir.ramtung.tinyme.domain.entity.Trade;
import org.springframework.stereotype.Component;

@Component
public class OwnershipControl implements MatchingControl {
//    public MatchingOutcome canStartMatching(Order order) {
//        if (order.getSide() == Side.SELL &&
//                !order.getShareholder().hasEnoughPositionsOn(order.getSecurity(),
//                        order.getSecurity().getOrderBook().totalSellQuantityByShareholder(order.getShareholder()) + order.getQuantity()))
//            return MatchingOutcome.NOT_ENOUGH_POSITIONS;
//        return MatchingOutcome.OK;
//    }
//    public void matchingAccepted(Order order, MatchResult result) {
//        for (Trade trade : result.trades()) {
//            trade.getBuy().getShareholder().incPosition(trade.getSecurity(), trade.getQuantity());
//            trade.getSell().getShareholder().decPosition(trade.getSecurity(), trade.getQuantity());
//        }
//    }
}
