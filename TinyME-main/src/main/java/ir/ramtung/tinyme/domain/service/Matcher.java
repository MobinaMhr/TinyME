package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.messaging.Message;
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
    public int reopeningPrice = 0;
    public int maxTradableQuantity = 0;

    private Trade createNewTradeFor(Order order, int price, Order matchingOrder) {
        return new Trade(order.getSecurity(), price, Math.min(order.getQuantity(),
                matchingOrder.getQuantity()), order, matchingOrder);
    }
    private MatchResult matchSLO(StopLimitOrder newSLOrder) { // TODO::rename to validateMatchSLO
        InactiveOrderBook inactiveOrderBook = newSLOrder.getSecurity().getInactiveOrderBook();
        // TODO::! notEnoughCredit() : 3
        if (newSLOrder.getSide() == Side.BUY && !newSLOrder.getBroker().hasEnoughCredit(newSLOrder.getPrice())) {
            return MatchResult.notEnoughCredit();
        }
        if (!newSLOrder.canMeetLastTradePrice(lastTradePrice)) {
            inactiveOrderBook.DeActive((Order) newSLOrder);
            return MatchResult.notMetLastTradePrice();
        }
        return null;
    }

    private MatchResult validateMatchedTrade(Trade trade, Order newOrder, LinkedList<Trade> trades) {
        // TODO::! notEnoughCredit() : 3
        if (newOrder.getSide() == Side.BUY && !trade.buyerHasEnoughCredit()) {
            rollbackTrades(newOrder, trades);
            return MatchResult.notEnoughCredit();
        }
        // TODO::! decreaseBuyersCredit() 1-2
        if (newOrder.getSide() == Side.BUY) trade.decreaseBuyersCredit();

        trade.increaseSellersCredit();
        trades.add(trade);
        return null;
    }

    private void updateOrderQuantities(Order newOrder, Order matchingOrder, OrderBook orderBook) {
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
            MatchResult tradeResult = validateMatchedTrade(trade, newOrder, trades);
            if (tradeResult != null) return tradeResult;
            updateOrderQuantities(newOrder, matchingOrder, orderBook);
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
            // TODO::! decreaseCreditBy() : 5
            newOrder.getBroker().increaseCreditBy(trades.stream().mapToLong(Trade::getTradedValue).sum());
            trades.forEach(trade -> trade.getSell().getBroker().decreaseCreditBy(trade.getTradedValue()));

            ListIterator<Trade> it = trades.listIterator(trades.size());
            while (it.hasPrevious()) {
                newOrder.getSecurity().getOrderBook().restoreOrder(it.previous().getSell());
            }
        } else if (newOrder.getSide() == Side.SELL) {
            // TODO::! decreaseCreditBy() : 5
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
    //TODO in chie mobinaaaaaaaaaaaaaaaaa???
    private boolean checkForMatchOutcome(MatchResult result, Order order) { // TODO::rename based on return type
        return switch (result.outcome()) {
            case NOT_ENOUGH_CREDIT -> true;
            case NOT_MET_LAST_TRADE_PRICE -> {
                // TODO? decreaseCreditBy() : 5
                if (order.getSide() == Side.BUY) order.getBroker().decreaseCreditBy((long) order.getPrice() * order.getQuantity());
                yield true;
            }
            default -> false;
        };
    }
    private boolean checkNewOrderNotMetMEQ(MatchResult result, Order order, int prevQuantity) {
        if (!(order.getStatus() == OrderStatus.NEW)) return false;
        if (isMEQFilterPassedBy(result.remainder(), prevQuantity)) return false;

        rollbackTrades(order, result.trades());
        return true;
    }
    //TODO bah bah
    private void updateShareholderPositions(List<Trade> trades) {
        for (Trade trade : trades) {
            trade.getBuy().getShareholder().incPosition(trade.getSecurity(), trade.getQuantity());
            trade.getSell().getShareholder().decPosition(trade.getSecurity(), trade.getQuantity());
        }
    }
    public MatchResult execute(Order order) {
        int prevQuantity = order.getQuantity();

        MatchResult result = match(order);
        if (checkForMatchOutcome(result, order)) return result;
        if (checkNewOrderNotMetMEQ(result, order, prevQuantity)) return MatchResult.notMetMEQValue();

        if (order.getStatus() == OrderStatus.NEW && !isMEQFilterPassedBy(result.remainder(), prevQuantity)){
            rollbackTrades(order, result.trades());
            return MatchResult.notMetMEQValue();
        }

        MatchResult remainderResult = handleTradeRemainder(result, order);
        if (remainderResult != null) return remainderResult;

        updateShareholderPositions(result.trades());
        if (!result.trades().isEmpty())
            lastTradePrice = result.trades().getLast().getPrice();
        return result;
    }
    private MatchResult handleTradeRemainder(MatchResult result, Order order) {
        if (result.remainder().getQuantity() <= 0) return null;
        // TODO::! notEnoughCredit() : 3
        if (order.getSide() == Side.BUY && !order.getBroker().hasEnoughCredit((long)order.getPrice() * order.getQuantity())) {
            rollbackTrades(order, result.trades());
            return MatchResult.notEnoughCredit();
        }

        // TODO::! decreaseCreditBy() : 5
        if (order.getSide() == Side.BUY) order.getBroker().decreaseCreditBy((long)order.getPrice() * order.getQuantity());

        order.getSecurity().getOrderBook().enqueue(result.remainder());
        return null;
    }

    public MatchResult auctionExecute(Order order) {
        // TODO::! notEnoughCredit() : 3
        if (order.getSide() == Side.BUY && !order.getBroker().hasEnoughCredit(order.getValue())) {
            return MatchResult.notEnoughCredit();
        }

        // TODO::! decreaseCreditBy() : 5
        if (order.getSide() == Side.BUY) order.getBroker().decreaseCreditBy(order.getValue());

        OrderBook orderBook = order.getSecurity().getOrderBook();
        orderBook.enqueue(order);
        calculateReopeningPrice(orderBook);
        return MatchResult.executed();
    }
}
