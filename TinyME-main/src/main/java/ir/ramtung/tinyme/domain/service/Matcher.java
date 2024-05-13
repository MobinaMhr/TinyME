package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import lombok.Getter;
import org.springframework.stereotype.Service;

import java.util.LinkedList;
import java.util.ListIterator;

@Getter
@Service
public class Matcher {
    private int lastTradePrice;
    private int reopeningPrice;

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

            Trade trade = new Trade(newOrder.getSecurity(), matchingOrder.getPrice(), Math.min(newOrder.getQuantity(), matchingOrder.getQuantity()), newOrder, matchingOrder);
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
    // TODO.
    public MatchResult auctionMatch(Order newOrder) {
        OrderBook orderBook = newOrder.getSecurity().getOrderBook();
        LinkedList<Order> sellQueue = new LinkedList<>();
        LinkedList<Order> buyQueue = new LinkedList<>();
        LinkedList<Trade> trades = new LinkedList<>();


        for (var order : orderBook.getSellQueue()){
            if(order.getPrice() >= this.reopeningPrice){
                orderBook.removeByOrderId(Side.SELL,order.getOrderId());
                sellQueue.add(order);
            }
        }

        for (var order : orderBook.getBuyQueue()){
            if(order.getPrice() <= this.reopeningPrice){
                orderBook.removeByOrderId(Side.BUY,order.getOrderId());
                order.getBroker().increaseCreditBy(order.getValue());
                buyQueue.add(order);
            }
        }
        Order baseOrder = null;
        for (var order : buyQueue)
        {
            baseOrder = order;
            while (!sellQueue.isEmpty() && order.getQuantity() > 0) {
                Order matchingOrder = sellQueue.getFirst();

                Trade trade = new Trade(order.getSecurity(), reopeningPrice, Math.min(order.getQuantity(), matchingOrder.getQuantity()), order, matchingOrder);
                trade.decreaseBuyersCredit();
                trade.increaseSellersCredit();
                trades.add(trade);

                if (newOrder.getQuantity() >= matchingOrder.getQuantity()) {
                    order.decreaseQuantity(matchingOrder.getQuantity());
                    sellQueue.remove(matchingOrder);
                    // TODO::iceberg match needs revision
//                    if (matchingOrder instanceof IcebergOrder icebergOrder) {
//                        icebergOrder.decreaseQuantity(matchingOrder.getQuantity());
//                        icebergOrder.replenish();
//                        if (icebergOrder.getQuantity() > 0) {
//                            orderBook.enqueue(icebergOrder);
//                        }
//                    }
                } else {
                    matchingOrder.decreaseQuantity(order.getQuantity());
                    order.makeQuantityZero();
                    buyQueue.remove(order);
                }
            }
        }
        for (var order : sellQueue){
            auctionExecute(order);
        }

        for (var order : buyQueue){
            auctionExecute(order);
        }

        return MatchResult.executedInAuction(baseOrder, trades);

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
        if (order instanceof StopLimitOrder stopLimitOrder) {
            return MatchResult.stopLimitOrderIsNotAllowedInAuction();
        }
        if (order.getMinimumExecutionQuantity() > 0) {
            return MatchResult.meqOrderIsNotAllowedInAuction();
        }
        // check for other types of order.TODO.

        if (order.getSide() == Side.BUY) {
            if (order.getBroker().getCredit() >= order.getValue())
                order.getBroker().decreaseCreditBy(order.getValue());
            else {
                return MatchResult.notEnoughCredit();
            }
        }

        OrderBook orderBook = order.getSecurity().getOrderBook();
        orderBook.enqueue(order);
        return MatchResult.executedInAuction();
    }
    // TODO. it has some getter
    private void calculateReopeningPrice(Order order) {
        reopeningPrice = 0;
        int tradedQuantity = -1;
        int lowestPriceInSellQueue = order.getSecurity().getOrderBook().getSellQueue().getLast().getPrice();
        int highestPriceInBuyQueue = order.getSecurity().getOrderBook().getBuyQueue().getLast().getPrice();

        for (int i=lowestPriceInSellQueue; i<=highestPriceInBuyQueue; i++){
            int temp = this.reopeningPrice;
            this.reopeningPrice = i;
            MatchResult result = auctionMatch(order);
            if (result.trades().stream().mapToLong(Trade::getQuantity).sum() < tradedQuantity){
                this.reopeningPrice = temp;
            }
            else if (Math.abs(this.reopeningPrice - lastTradePrice) > Math.abs(temp - lastTradePrice)){
                this.reopeningPrice = temp;
            }
            else {
                this.reopeningPrice = temp;
            }
        }
    }
    // TODO : add new method to do bazgoshayii process
    // compute or get the bazgoshayii price
    // for IcebergeOrder, you should mention all quantity to take rule, not the displayed quantity.
    // find orders which could participate in some trades with this price (satisfication)
    // enter in Trades queue (I'm not sure find in code) sequentially.
    // call auctionMatch on trade queue so auctionExecute will be called too.
    // untill one queue became empty.
    // enter remaining orders from trade queue to orderbook. sequentially
    // after match give or get difference between bazgoshayii price and the price they've paid..
    // *** This price should participate in a rokhdade gheimate bazgoshayii (maybe somewhere else, may not.)
}
