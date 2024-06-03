package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.Order;
import ir.ramtung.tinyme.domain.entity.Side;
import ir.ramtung.tinyme.domain.entity.Trade;
import org.springframework.stereotype.Component;

import java.util.LinkedList;
import java.util.ListIterator;

@Component
public class CreditControl implements MatchingControl {
//    @Override
//    public MatchingOutcome canTrade(Order newOrder, Trade trade) {
//        if ((newOrder.getSide() == Side.SELL) || (newOrder.getSide() == Side.BUY && trade.buyerHasEnoughCredit())) {
//            return MatchingOutcome.OK;
//        } else return MatchingOutcome.NOT_ENOUGH_CREDIT;
//    }
//
//    @Override
//    public void tradeAccepted(Order newOrder, Trade trade) {
//        if (newOrder.getSide() == Side.BUY)
//            trade.decreaseBuyersCredit();
//        trade.increaseSellersCredit();
//    }
//
//    @Override
//    public MatchingOutcome canAcceptMatching(Order order, MatchResult result) {
//        if (result.remainder().getQuantity() > 0) {
//            if (order.getSide() == Side.BUY) {
//                if (!order.getBroker().hasEnoughCredit(order.getValue()))
//                    return MatchingOutcome.NOT_ENOUGH_CREDIT;
//            }
//        }
//        return MatchingOutcome.OK;
//    }
//
//    @Override
//    public void matchingAccepted(Order order, MatchResult result) {
//        if (order.getSide() == Side.BUY) {
//            order.getBroker().decreaseCreditBy(order.getValue());
//        }
//    }
//
//    @Override
//    public void rollbackTrades(Order newOrder, LinkedList<Trade> trades) {
//        if (newOrder.getSide() == Side.BUY) {
//            newOrder.getBroker().increaseCreditBy(trades.stream().mapToLong(Trade::getTradedValue).sum());
//            trades.forEach(trade -> trade.getSell().getBroker().decreaseCreditBy(trade.getTradedValue()));
//        } else {
//            newOrder.getBroker().decreaseCreditBy(trades.stream().mapToLong(Trade::getTradedValue).sum());
//            trades.forEach(trade -> trade.getSell().getBroker().increaseCreditBy(trade.getTradedValue()));
//        }
//    }
}
