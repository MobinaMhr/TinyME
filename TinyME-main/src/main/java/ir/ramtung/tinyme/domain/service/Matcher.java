package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.control.MatchingControlList;
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

    private int getTradableQuantity(int orgPrice, LinkedList<Order> oppQueue, Side orgSide) {
        int tradableQuantityOpp = 0;
        for(Order order:oppQueue) {
            if (orgSide == Side.BUY && order.getPrice() > orgPrice) break;
            if (orgSide == Side.SELL && orgPrice > order.getPrice()) break;
            tradableQuantityOpp += order.getTotalQuantity();
        }
        return tradableQuantityOpp;
    }

    private int getLastTradeReopeningPriceDiff() {
        return Math.abs(lastTradePrice - this.reopeningPrice);
    }
    private int getLastTradeOrderPriceDiff(int orderPrice) {
        return Math.abs(lastTradePrice - orderPrice);
    }
    private int getTradableQuantityForPrice(int orderPrice, Side side, OrderBook orderBook) {
        return  (side == Side.SELL) ?
                getTradableQuantity(orderPrice, orderBook.getBuyQueue(), side) :
                getTradableQuantity(orderPrice, orderBook.getSellQueue(), side);
    }
    private void calculateBestReopeningPriceInQueue(LinkedList<Order> queue, OrderBook orderBook, Side side) {
        int tradableQuantity = 0;
        for(Order order: queue) {
            tradableQuantity += order.getTotalQuantity();
            int tradableQuantityOpp = getTradableQuantityForPrice(order.getPrice(), side, orderBook);
            int exchangedQuantity = Math.min(tradableQuantityOpp, tradableQuantity);

            if (exchangedQuantity < this.maxTradableQuantity) continue;
            if (exchangedQuantity > this.maxTradableQuantity) {
                this.reopeningPrice = order.getPrice();
                this.maxTradableQuantity = exchangedQuantity;
            }
            else {
                if (getLastTradeReopeningPriceDiff() > getLastTradeOrderPriceDiff(order.getPrice())) {
                    this.reopeningPrice = order.getPrice();
                }
                else if (getLastTradeReopeningPriceDiff() == getLastTradeOrderPriceDiff(order.getPrice())) {
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

        int maxQuantityWithLastPrice = Math.min(getTradableQuantity(lastTradePrice, orderBook.getBuyQueue(), Side.BUY),
                getTradableQuantity(lastTradePrice, orderBook.getBuyQueue(), Side.SELL));

        if (maxQuantityWithLastPrice == this.maxTradableQuantity) this.reopeningPrice = lastTradePrice;
        if (maxTradableQuantity == 0) this.reopeningPrice = 0;
    }

    private MatchResult canMatchSLO(StopLimitOrder sloOrder) {
        MatchingOutcome outcome;
        outcome = controls.canTrade(sloOrder, null);
        if (outcome != MatchingOutcome.OK) return MatchResult.notEnoughCredit();
        InactiveOrderBook inactiveOrderBook = sloOrder.getSecurity().getInactiveOrderBook();
        if (!sloOrder.canMeetLastTradePrice(lastTradePrice)) {
            inactiveOrderBook.DeActive((Order) sloOrder);
            return MatchResult.notMetLastTradePrice();
        }
        return null;
    }
    public MatchResult match(Order newOrder) {
        MatchingOutcome outcome;
        if (newOrder instanceof StopLimitOrder sloOrder) {
            MatchResult result = canMatchSLO(sloOrder);
            if (result != null) return result;
        }

        OrderBook orderBook = newOrder.getSecurity().getOrderBook();
        LinkedList<Trade> trades = new LinkedList<>();
        while (orderBook.hasOrderOfType(newOrder.getSide().opposite()) && newOrder.getQuantity() > 0) {
            Order matchingOrder = orderBook.matchWithFirst(newOrder);
            if (matchingOrder == null) break;
            Trade trade = createNewTradeFor(newOrder, matchingOrder.getPrice(), matchingOrder);
            outcome = controls.canTrade(newOrder, trade);
            if (outcome != MatchingOutcome.OK) {
                controls.rollbackTrades(newOrder, trades);
                return MatchResult.notEnoughCredit();
            }
            trades.add(trade);
            controls.tradeAccepted(newOrder, trade);
            controls.tradeQuantityUpdated(newOrder, matchingOrder, MatchingState.CONTINUOUS);
        }
        return MatchResult.executed(newOrder, trades);
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
            controls.tradeAccepted(buyOrder, trade);
            controls.matchingAccepted(buyOrder, MatchResult.executed(List.of(trade)));
            trades.add(trade);

            controls.tradeQuantityUpdated(buyOrder, sellOrder, MatchingState.AUCTION);
            buyOrder.getBroker().decreaseCreditBy(buyOrder.getValue());
        }
        return trades;
    }

    public MatchResult execute(Order order) {
        MatchingOutcome outcome;

        outcome = controls.canStartMatching(order);
        if (outcome != MatchingOutcome.OK) return new MatchResult(outcome, null);

        int prevQuantity = order.getQuantity();

        MatchResult result = match(order);
        outcome = result.outcome();
        if (outcome == MatchingOutcome.NOT_ENOUGH_CREDIT) return result;
        if (result.outcome() == MatchingOutcome.NOT_MET_LAST_TRADE_PRICE) {
            controls.orderAccepted(order);
            return result;
        }

        outcome = controls.doesMetMEQValue(order, result, prevQuantity);
        if (outcome != MatchingOutcome.OK) {
            controls.rollbackTrades(order, result.trades());
            return new MatchResult(outcome, null);
        }

        outcome = controls.canAcceptMatching(order, result);
        if (outcome != MatchingOutcome.OK) {
            controls.rollbackTrades(order, result.trades());
            return new MatchResult(outcome, null);
        }

        if (result.remainder().getQuantity() > 0) order.getSecurity().getOrderBook().enqueue(result.remainder());

        controls.orderAccepted(order);
        controls.matchingAccepted(order, result);
        if (!result.trades().isEmpty()) lastTradePrice = result.trades().getLast().getPrice();

        return result;
    }

    public MatchResult auctionExecute(Order order) {
        OrderBook orderBook = order.getSecurity().getOrderBook();
        MatchingOutcome outcome;

        outcome = controls.canStartMatching(order);
        if (outcome != MatchingOutcome.OK) return new MatchResult(outcome, order);

        outcome = controls.canTrade(order, null);
        if (outcome != MatchingOutcome.OK) return MatchResult.notEnoughCredit();

        controls.orderAccepted(order);

        orderBook.enqueue(order);
        calculateReopeningPrice(orderBook);

        return MatchResult.executed();
    }
}
