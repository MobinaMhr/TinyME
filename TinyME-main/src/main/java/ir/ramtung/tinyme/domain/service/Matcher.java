package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Service;

import java.util.*;

@Getter
@Service
public class Matcher {
    @Setter
    private int lastTradePrice;
    //Default values
    public int reopeningPrice = 0;
    public int maxTradableQuantity = 0;

    private void updateLastTradePrice(MatchResult result) {
        if (result.trades().isEmpty()) {
            return;
        }
        lastTradePrice = result.trades().getLast().getPrice();
    }

    public MatchResult match(Order newOrder) {
        OrderBook orderBook = newOrder.getSecurity().getOrderBook();
        InactiveOrderBook inactiveOrderBook = newOrder.getSecurity().getInactiveOrderBook();
        LinkedList<Trade> trades = new LinkedList<>();

        if (newOrder instanceof StopLimitOrder stopLimitOrder) {
            if (stopLimitOrder.getSide() == Side.BUY
                    && !stopLimitOrder.getBroker().hasEnoughCredit(stopLimitOrder.getPrice())) {
                return MatchResult.notEnoughCredit();
            }
            if (!stopLimitOrder.canMeetLastTradePrice(lastTradePrice)) {
                inactiveOrderBook.DeActive(newOrder);
                return MatchResult.notMetLastTradePrice();
            }
        }

        while (orderBook.hasOrderOfType(newOrder.getSide().opposite()) && newOrder.getQuantity() > 0) {
            Order matchingOrder = orderBook.matchWithFirst(newOrder);
            if (matchingOrder == null) {
                break;
            }

            Trade trade = new Trade(newOrder.getSecurity(), matchingOrder.getPrice(),
                    Math.min(newOrder.getQuantity(), matchingOrder.getQuantity()), newOrder, matchingOrder);
            if (newOrder.getSide() == Side.BUY) {
                if (trade.buyerHasEnoughCredit())
                    trade.decreaseBuyersCredit();
                else {
                    rollbackTrades(newOrder, trades);
                    return MatchResult.notEnoughCredit();
                }
            }
            trade.increaseSellersCredit();
            trades.add(trade);

            if (newOrder.getQuantity() >= matchingOrder.getQuantity()) {
                newOrder.decreaseQuantity(matchingOrder.getQuantity());
                orderBook.removeFirst(matchingOrder.getSide());
                if (matchingOrder instanceof IcebergOrder icebergOrder) {
                    icebergOrder.decreaseQuantity(matchingOrder.getQuantity());
                    icebergOrder.replenish();
                    if (icebergOrder.getQuantity() > 0) {
                        orderBook.enqueue(icebergOrder);
                    }
                }
            } else {
                matchingOrder.decreaseQuantity(newOrder.getQuantity());
                newOrder.makeQuantityZero();
            }
        }
        return MatchResult.executed(newOrder, trades);
    }

    private int checkOppQueue(int orgPrice, LinkedList<Order> oppQueue, Side orgSide){
        int tradableQuantityOpp = 0;
        for(Order order:oppQueue){
            if((orgSide == Side.BUY && order.getPrice() > orgPrice) || (orgSide == Side.SELL && orgPrice > order.getPrice()))
                break;
            tradableQuantityOpp += order.getTotalQuantity();
        }
        return tradableQuantityOpp;
    }

    private void calculateBestReopeningPriceInQueue(LinkedList<Order> queue, OrderBook orderBook, Side side){
        int tradableQuantity = 0;
        int tradableQuantityOpp = 0;
        for(Order order: queue){
            tradableQuantity += order.getTotalQuantity();
            if(side == Side.BUY)
                tradableQuantityOpp = checkOppQueue(order.getPrice(), orderBook.getSellQueue(), side);
            else
                tradableQuantityOpp = checkOppQueue(order.getPrice(), orderBook.getBuyQueue(), side);
            int exchangedQuantity = Math.min(tradableQuantityOpp, tradableQuantity);
            if(exchangedQuantity > this.maxTradableQuantity){
                this.reopeningPrice = order.getPrice();
                this.maxTradableQuantity = exchangedQuantity;
            } else if (exchangedQuantity == this.maxTradableQuantity){
                if(Math.abs(lastTradePrice - this.reopeningPrice) > Math.abs(lastTradePrice - order.getPrice())) {
                    this.reopeningPrice = order.getPrice();
                } else if (Math.abs(lastTradePrice - this.reopeningPrice) == Math.abs(lastTradePrice - order.getPrice())) {
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
        if(maxQuantityWithLastPrice == this.maxTradableQuantity)
            this.reopeningPrice = lastTradePrice;

        if(maxTradableQuantity == 0){
            this.reopeningPrice = 0;
        }
    }

    public void removeOrdersWithZeroQuantity(Order order, Side side, OrderBook orderBook) {
        if (order.getQuantity() != 0) {
            return;
        }

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
        while (true){
            LinkedList<Order> buyOrders = orderBook.getOpeningBuyOrders(this.reopeningPrice);
            LinkedList<Order> sellOrders = orderBook.getOpeningSellOrders(this.reopeningPrice);
            if (buyOrders.isEmpty() || sellOrders.isEmpty())
                break;

            Order buyOrder = buyOrders.getFirst();
            Order sellOrder = sellOrders.getFirst();
            Trade trade = new Trade(buyOrder.getSecurity(), this.reopeningPrice,
                    Math.min(buyOrder.getQuantity(), sellOrder.getQuantity()),
                    buyOrder, sellOrder);

            buyOrder.getBroker().increaseCreditBy(buyOrder.getValue());
            trade.decreaseBuyersCredit();
            trade.increaseSellersCredit();
            trades.add(trade);

            int tradedQuantity = 0;
            tradedQuantity = Math.min(buyOrder.getQuantity(), sellOrder.getQuantity());
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

    private boolean isMEQFilterPassedBy(Order remainder, int initialQuantity){
        return (initialQuantity - remainder.getQuantity()) >= remainder.getMinimumExecutionQuantity();
    }

    public MatchResult execute(Order order) {
        int prevQuantity = order.getQuantity();
        MatchResult result = match(order);

        if (result.outcome() == MatchingOutcome.NOT_ENOUGH_CREDIT)
            return result;

        if (result.outcome() == MatchingOutcome.NOT_MET_LAST_TRADE_PRICE){
            if (order.getSide() == Side.BUY)
                order.getBroker().decreaseCreditBy((long)order.getPrice() * order.getQuantity());
            return result;
        }

        if (order.getStatus() == OrderStatus.NEW && !isMEQFilterPassedBy(result.remainder(), prevQuantity)){
            rollbackTrades(order, result.trades());
            return MatchResult.notMetMEQValue();
        }

        if (result.remainder().getQuantity() > 0) {
            if (order.getSide() == Side.BUY) {
                if (!order.getBroker().hasEnoughCredit((long)order.getPrice() * order.getQuantity())) {
                    rollbackTrades(order, result.trades());
                    return MatchResult.notEnoughCredit();
                }
                order.getBroker().decreaseCreditBy((long)order.getPrice() * order.getQuantity());
            }
            order.getSecurity().getOrderBook().enqueue(result.remainder());
        }

        for (Trade trade : result.trades()) {
            trade.getBuy().getShareholder().incPosition(trade.getSecurity(), trade.getQuantity());
            trade.getSell().getShareholder().decPosition(trade.getSecurity(), trade.getQuantity());
        }

        updateLastTradePrice(result);
        return result;
    }
    public MatchResult auctionExecute(Order order) {
        if (order.getSide() == Side.BUY) {
            if (order.getBroker().getCredit() < order.getValue())
                return MatchResult.notEnoughCredit();
            order.getBroker().decreaseCreditBy(order.getValue());
        }

        OrderBook orderBook = order.getSecurity().getOrderBook();
        orderBook.enqueue(order);

        calculateReopeningPrice(orderBook);

        return MatchResult.executed();
    }
}
