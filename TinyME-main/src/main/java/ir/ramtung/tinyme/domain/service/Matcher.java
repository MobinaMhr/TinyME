package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.control.MatchingControlList;
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

    private MatchResult canStartMatchingStopLimitOrder(StopLimitOrder newSLOrder) {
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
            MatchResult sloResult = canStartMatchingStopLimitOrder(stopLimitOrder);
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
            controls.tradeQuantityUpdated(newOrder, matchingOrder, trade);
        }
        return MatchResult.executed(newOrder, trades);
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

    private void calculateBestReopeningPriceInQueue(LinkedList<Order> queue, OrderBook orderBook, Side side) {
        //TODO:rename to getter function and set the return type as in
        int tradableQuantity = 0;
        int tradableQuantityOpp;

        for(Order order: queue) {
            tradableQuantity += order.getTotalQuantity();

            tradableQuantityOpp = (side == Side.SELL) ?
                    getTradableQuantity(order.getPrice(), orderBook.getBuyQueue(), side) :
                    getTradableQuantity(order.getPrice(), orderBook.getSellQueue(), side);

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

        int maxQuantityWithLastPrice = Math.min(getTradableQuantity(lastTradePrice, orderBook.getBuyQueue(), Side.BUY),
                getTradableQuantity(lastTradePrice, orderBook.getBuyQueue(), Side.SELL));

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
            controls.tradeAccepted(buyOrder, trade);
            controls.matchingAccepted(buyOrder, MatchResult.executed(List.of(trade))); //TODO in dorost mikone benazaram share haro
            trades.add(trade);

//            controls.tradeQuantityUpdated(buyOrder, sellOrder, trade);

            int tradedQuantity = Math.min(buyOrder.getQuantity(), sellOrder.getQuantity());
            // TODO in bayad tradeQuantityUpdated ro baraye halate auction ham besazim
            buyOrder.decreaseQuantity(tradedQuantity);
            sellOrder.decreaseQuantity(tradedQuantity);

            removeOrdersWithZeroQuantity(buyOrder, Side.BUY, orderBook);
            removeOrdersWithZeroQuantity(sellOrder, Side.SELL, orderBook);

            buyOrder.getBroker().decreaseCreditBy(buyOrder.getValue());
        }
        return trades;
    }

    public MatchResult execute(Order order) {
        OrderBook orderBook = order.getSecurity().getOrderBook();

        int position = orderBook.totalSellQuantityByShareholder(order.getShareholder()) + order.getQuantity();
        if (order.getSide() == Side.SELL
                && !order.getShareholder().hasEnoughPositionsOn(order.getSecurity(), position)) {
            return MatchResult.notEnoughPositions();
        }

        int prevQuantity = order.getQuantity();

        MatchResult result = match(order);
        if (result.outcome() == MatchingOutcome.NOT_ENOUGH_CREDIT) return result;

        if (result.outcome() == MatchingOutcome.NOT_MET_LAST_TRADE_PRICE) {
            controls.orderAccepted(order);
            return result;
        }

        if (controls.doesMetMEQValue(order, result, prevQuantity)!= MatchingOutcome.OK) {
            controls.rollbackTrades(order, result.trades());
            return MatchResult.notMetMEQValue();
        }

        if(controls.canAcceptMatching(order, result) != MatchingOutcome.OK){
            controls.rollbackTrades(order, result.trades());
            return MatchResult.notEnoughCredit();
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
