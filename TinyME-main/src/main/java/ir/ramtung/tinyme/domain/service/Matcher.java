package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Getter
@Service
public class Matcher {
    @Setter
    private int lastTradePrice;
    public int reopeningPrice = 0;
    public int maxTradableQuantity = 0;

    @Autowired
    public MatchingControlList controls;


    private Trade createNewTradeFor(Order order, int price, Order matchingOrder) {
        return new Trade(order.getSecurity(), price, Math.min(order.getQuantity(),
                matchingOrder.getQuantity()), order, matchingOrder);
    }
    private MatchResult matchSLO(StopLimitOrder newSLOrder) { // TODO::rename to validateMatchSLO
        InactiveOrderBook inactiveOrderBook = newSLOrder.getSecurity().getInactiveOrderBook();
        if (controls.canTrade(newSLOrder, null) != MatchingOutcome.OK) {
            return MatchResult.notEnoughCredit();
        }
        if (!newSLOrder.canMeetLastTradePrice(lastTradePrice)) {
            inactiveOrderBook.DeActive((Order) newSLOrder);
            return MatchResult.notMetLastTradePrice();
        }
        return null;
    }
    public MatchResult match(Order newOrder) {
        OrderBook orderBook = newOrder.getSecurity().getOrderBook();

        if (newOrder instanceof StopLimitOrder stopLimitOrder) {
            MatchResult sloResult = matchSLO(stopLimitOrder);
            if (sloResult != null) return sloResult;
        }

        LinkedList<Trade> trades = new LinkedList<>();
        while (orderBook.hasOrderOfType(newOrder.getSide().opposite()) && newOrder.getQuantity() > 0) {
            Order matchingOrder = orderBook.matchWithFirst(newOrder);
            if (matchingOrder == null) break;
            Trade trade = createNewTradeFor(newOrder, matchingOrder.getPrice(), matchingOrder);

            if (controls.canTrade(newOrder, trade) != MatchingOutcome.OK) {
                controls.rollbackTrades(newOrder, trades);
                return MatchResult.notEnoughCredit();
            }

            trades.add(trade);
            controls.tradeAccepted(newOrder, trade);
            controls.tradeAccepted2(newOrder, matchingOrder, trade);
        }
        return MatchResult.executed(newOrder, trades);
    }

    private int checkOppQueue(int orgPrice, LinkedList<Order> oppQueue, Side orgSide) { //TODO::Rename
        int tradableQuantityOpp = 0;
        for(Order order:oppQueue) {
            if (orgSide == Side.BUY && order.getPrice() > orgPrice) break;
            if (orgSide == Side.SELL && orgPrice > order.getPrice()) break;
            tradableQuantityOpp += order.getTotalQuantity();
        }
        return tradableQuantityOpp;
    }

    private void calculateBestReopeningPriceInQueue(LinkedList<Order> queue, OrderBook orderBook, Side side) {
        //TODO:rename to getter function and set the return type as in
        int tradableQuantity = 0;
        int tradableQuantityOpp;

        for(Order order: queue) {
            tradableQuantity += order.getTotalQuantity();

            tradableQuantityOpp = (side == Side.SELL) ?
                    checkOppQueue(order.getPrice(), orderBook.getBuyQueue(), side) :
                    checkOppQueue(order.getPrice(), orderBook.getSellQueue(), side);

            int exchangedQuantity = Math.min(tradableQuantityOpp, tradableQuantity);
            if(exchangedQuantity > this.maxTradableQuantity){
                this.reopeningPrice = order.getPrice();
                this.maxTradableQuantity = exchangedQuantity;
            }
            else if (exchangedQuantity == this.maxTradableQuantity){
                if(Math.abs(lastTradePrice - this.reopeningPrice) > Math.abs(lastTradePrice - order.getPrice())) {
                    this.reopeningPrice = order.getPrice();
                }
                else if (Math.abs(lastTradePrice - this.reopeningPrice) == Math.abs(lastTradePrice - order.getPrice())) {
                    this.reopeningPrice = Math.min(this.reopeningPrice, order.getPrice());
                }
            }
        }
    }

    public void calculateReopeningPrice(OrderBook orderBook) {
        this.reopeningPrice = 0;
        this.maxTradableQuantity = 0;

        calculateBestReopeningPriceInQueue(orderBook.getBuyQueue(), orderBook, Side.BUY);
        calculateBestReopeningPriceInQueue(orderBook.getSellQueue(), orderBook, Side.SELL);

        int maxQuantityWithLastPrice = Math.min(checkOppQueue(lastTradePrice, orderBook.getBuyQueue(), Side.BUY),
                checkOppQueue(lastTradePrice, orderBook.getBuyQueue(), Side.SELL));

        if (maxQuantityWithLastPrice == this.maxTradableQuantity) this.reopeningPrice = lastTradePrice;
        if (maxTradableQuantity == 0) this.reopeningPrice = 0;
    }

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

    public LinkedList<Trade> auctionMatch(OrderBook orderBook) {
        LinkedList<Trade> trades = new LinkedList<>();
        while (true) {
            LinkedList<Order> buyOrders = orderBook.getOpeningBuyOrders(this.reopeningPrice);
            LinkedList<Order> sellOrders = orderBook.getOpeningSellOrders(this.reopeningPrice);
            if (buyOrders.isEmpty() || sellOrders.isEmpty()) break;

            Order buyOrder = buyOrders.getFirst();
            Order sellOrder = sellOrders.getFirst();

            Trade trade = createNewTradeFor(buyOrder, this.reopeningPrice, sellOrder);

            buyOrder.getBroker().increaseCreditBy(buyOrder.getValue());
            trade.decreaseBuyersCredit();
            trade.increaseSellersCredit();
            trades.add(trade);

            int tradedQuantity = Math.min(buyOrder.getQuantity(), sellOrder.getQuantity());
            buyOrder.decreaseQuantity(tradedQuantity);
            sellOrder.decreaseQuantity(tradedQuantity);

            removeOrdersWithZeroQuantity(buyOrder, Side.BUY, orderBook);
            removeOrdersWithZeroQuantity(sellOrder, Side.SELL, orderBook);

            buyOrder.getBroker().decreaseCreditBy(buyOrder.getValue());
        }
        return trades;
    }

    private void rollbackTrades(Order newOrder, LinkedList<Trade> trades) {
        if (newOrder.getSide() == Side.BUY) {
            newOrder.getBroker().increaseCreditBy(trades.stream().mapToLong(Trade::getTradedValue).sum());
            trades.forEach(trade -> trade.getSell().getBroker().decreaseCreditBy(trade.getTradedValue()));

            ListIterator<Trade> it = trades.listIterator(trades.size());
            while (it.hasPrevious()) {
                newOrder.getSecurity().getOrderBook().restoreOrder(it.previous().getSell());
            }
        } else if (newOrder.getSide() == Side.SELL) {
            newOrder.getBroker().decreaseCreditBy(trades.stream().mapToLong(Trade::getTradedValue).sum());

            ListIterator<Trade> it = trades.listIterator(trades.size());
            while (it.hasPrevious()) {
                newOrder.getSecurity().getOrderBook().restoreOrder(it.previous().getBuy());
            }
        }
    }

    public MatchResult execute(Order order) {
        int prevQuantity = order.getQuantity();

        MatchResult result = match(order);
        if (result.outcome() == MatchingOutcome.NOT_ENOUGH_CREDIT) return result;

        if (result.outcome() == MatchingOutcome.NOT_MET_LAST_TRADE_PRICE) {
            if (order.getSide() == Side.BUY)
                order.getBroker().decreaseCreditBy((long) order.getPrice() * order.getQuantity());
            return result;
        }

        if (order.getStatus() == OrderStatus.NEW &&
                !result.remainder().minimumExecutionQuantitySatisfied(prevQuantity)) {
            rollbackTrades(order, result.trades());
            return MatchResult.notMetMEQValue();
        }

        if(controls.canAcceptMatching(order, result) != MatchingOutcome.OK){
            controls.rollbackTrades(order, result.trades());
            return MatchResult.notEnoughCredit();
        }

        if (result.remainder().getQuantity() > 0) order.getSecurity().getOrderBook().enqueue(result.remainder());
        controls.matchingAccepted(order, result);
        if (!result.trades().isEmpty()) lastTradePrice = result.trades().getLast().getPrice();

        return result;
    }

    public MatchResult auctionExecute(Order order) {
        if (controls.canTrade(order, null) != MatchingOutcome.OK)
            return MatchResult.notEnoughCredit();

//        matchingAccepted add value to  input list and rename to matchingConditionsAccepted?
        if (order.getSide() == Side.BUY) order.getBroker().decreaseCreditBy(order.getValue());

        OrderBook orderBook = order.getSecurity().getOrderBook();
        orderBook.enqueue(order);
        calculateReopeningPrice(orderBook);

        return MatchResult.executed();
    }
}
