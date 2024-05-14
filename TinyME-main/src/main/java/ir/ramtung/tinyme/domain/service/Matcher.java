package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import lombok.Getter;
import org.springframework.stereotype.Service;

import java.util.*;

@Getter
@Service
public class Matcher {
    private int lastTradePrice;
    public int reopeningPrice = -1;//Default value
    public int tradableQuantity = -1; //Default value

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

    // for IcebergeOrder, you should mention all quantity to take rule, not the displayed quantity.
    // *** This price should participate in a rokhdade gheimate bazgoshayii (maybe somewhere else, may not.)
    private static int calculateReopeningPrice(OrderBook orderBook) {
        int reopeningPrice = 0;
        int tradableQuantity = 0;

        int lowestPrice = Math.min(0, 1); // Temporary
        int highestPrice = Math.min(0, 1); // Temporary
//        int lowestPriceInSellQueue = order.getSecurity().getOrderBook().getSellQueue().getLast().getPrice();
//        int highestPriceInBuyQueue = order.getSecurity().getOrderBook().getBuyQueue().getLast().getPrice();
        for (int price = lowestPrice; price <= highestPrice; price++) {
//            int temp = this.reopeningPrice;
//            this.reopeningPrice = i;
//            MatchResult result = auctionMatch(order);
//            if (result.trades().stream().mapToLong(Trade::getQuantity).sum() < tradedQuantity)
//                this.reopeningPrice = temp;
//            else if (Math.abs(this.reopeningPrice - lastTradePrice) > Math.abs(temp - lastTradePrice))
//                this.reopeningPrice = temp;
//            else
//                this.reopeningPrice = temp;
        }
        return reopeningPrice;
    }

    public static MatchResult auctionMatch(OrderBook orderBook) {
        int reopeningPrice = calculateReopeningPrice(orderBook);
        // Remove this and get from attributes because new event should not be published

        LinkedList<Order> sellQueue = new LinkedList<>();
        for (var order : orderBook.getSellQueue()) {
            if (reopeningPrice < order.getPrice())
                continue;
            orderBook.removeByOrderId(Side.SELL, order.getOrderId());//TODO:will it remove from global orderbook?
            sellQueue.add(order);
        }

        LinkedList<Order> buyQueue = new LinkedList<>();
        for (var order : orderBook.getBuyQueue()) {
            if (reopeningPrice > order.getPrice())
                continue;
            orderBook.removeByOrderId(Side.BUY, order.getOrderId());
            buyQueue.add(order);
        }

        List<Map.Entry<Order, LinkedList<Trade>>> tradePair = new ArrayList<>();
        LinkedList<Trade> trades = new LinkedList<>();
        for (var buyOrder : buyQueue) {
            while (!sellQueue.isEmpty() && buyOrder.getQuantity() > 0) {
                Order matchingSellOrder = sellQueue.getFirst();
                Trade trade = new Trade(buyOrder.getSecurity(), reopeningPrice, Math.min(buyOrder.getQuantity(),
                        matchingSellOrder.getQuantity()), buyOrder, matchingSellOrder);
                trade.decreaseBuyersCredit();
                trade.increaseSellersCredit();
                trades.add(trade);

                // Add credit so in auctionExecute the new value will be decreased from buyOrder:>
                buyOrder.getBroker().increaseCreditBy(buyOrder.getValue());

                if (buyOrder.getQuantity() > matchingSellOrder.getQuantity()) {
                    buyOrder.decreaseQuantity(matchingSellOrder.getQuantity());
                    sellQueue.remove(matchingSellOrder);
                } else if (buyOrder.getQuantity() == matchingSellOrder.getQuantity()) {
                    buyOrder.makeQuantityZero();
                    buyQueue.remove(buyOrder);

                    matchingSellOrder.makeQuantityZero();
                    sellQueue.remove(matchingSellOrder);
                } else { // buyOrder.getQuantity() < matchingSellOrder.getQuantity()
                    matchingSellOrder.decreaseQuantity(buyOrder.getQuantity());
                    buyQueue.remove(buyOrder);
                }
            }
            tradePair.add(Map.entry(buyOrder, trades));
        }

        for (Trade trade : trades) {
            // craete new event publisher for TradeEvent
        }

        // Make sure this is global OrderBook. I mean in this method we removed these orders from orderBook,
        // are they really removed???? if you didn't get, contact me(Mobina).
        for (var order : sellQueue){
            auctionExecute(order);
        }

        for (var order : buyQueue){
            auctionExecute(order);
        }

//        MatchResult result = null;
//        return result;
//        return MatchResult.executedInAuction(baseOrder, trades);
        MatchResult.executedInAuction(tradePair);
        return MatchResult.executedInAuction(); //TODO
    }
//    LinkedList<MatchResult> executedInAuction

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
    public static MatchResult auctionExecute(Order order) {
        if (order instanceof StopLimitOrder stopLimitOrder) {
            return MatchResult.stopLimitOrderIsNotAllowedInAuction();
        }
        if (order.getMinimumExecutionQuantity() > 0) {
            return MatchResult.meqOrderIsNotAllowedInAuction();
        }
        // check for other types of order.TODO.

        if (order.getSide() == Side.BUY) {
            if (order.getBroker().getCredit() < order.getValue())
                return MatchResult.notEnoughCredit();
            order.getBroker().decreaseCreditBy(order.getValue());
        }

        OrderBook orderBook = order.getSecurity().getOrderBook();
        orderBook.enqueue(order);

        calculateReopeningPrice(orderBook);

        return MatchResult.executedInAuction();
    }
}
