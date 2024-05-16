package ir.ramtung.tinyme.domain.entity;

import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import lombok.Builder;
import lombok.Getter;

import java.util.LinkedList;
import java.util.List;

@Getter
@Builder
public class Security {
    private String isin;
    @Builder.Default
    private int tickSize = 1;
    @Builder.Default
    private int lotSize = 1;
    @Builder.Default
    private OrderBook orderBook = new OrderBook();
    @Builder.Default
    private InactiveOrderBook inactiveOrderBook = new InactiveOrderBook();
    @Builder.Default
    private MatchingState currentMatchingState = MatchingState.CONTINUOUS;

    public MatchResult newOrder(EnterOrderRq enterOrderRq, Broker broker, Shareholder shareholder, Matcher matcher) {
        if (enterOrderRq.getSide() == Side.SELL &&
                !shareholder.hasEnoughPositionsOn(this,
                        orderBook.totalSellQuantityByShareholder(shareholder) + enterOrderRq.getQuantity()))
            return MatchResult.notEnoughPositions();

        Order order;
        if (enterOrderRq.getPeakSize() == 0 && enterOrderRq.getStopPrice() == 0)
            order = new Order(enterOrderRq.getOrderId(), this, enterOrderRq.getSide(),
                    enterOrderRq.getQuantity(), enterOrderRq.getPrice(), broker, shareholder, enterOrderRq.getEntryTime(),
                    OrderStatus.NEW, enterOrderRq.getMinimumExecutionQuantity());
        else if (enterOrderRq.getPeakSize() > 0 && enterOrderRq.getStopPrice() == 0)
            order = new IcebergOrder(enterOrderRq.getOrderId(), this, enterOrderRq.getSide(),
                    enterOrderRq.getQuantity(), enterOrderRq.getPrice(), broker, shareholder,
                    enterOrderRq.getEntryTime(), enterOrderRq.getPeakSize(), OrderStatus.NEW,
                    enterOrderRq.getMinimumExecutionQuantity());
        else
            order = new StopLimitOrder(enterOrderRq.getOrderId(), this, enterOrderRq.getSide(),
                    enterOrderRq.getQuantity(), enterOrderRq.getPrice(), broker, shareholder,
                    enterOrderRq.getEntryTime(), OrderStatus.NEW, enterOrderRq.getStopPrice());


        MatchResult result = null;
        if (currentMatchingState == MatchingState.AUCTION) {
            if (order instanceof StopLimitOrder) {
                return MatchResult.stopLimitOrderIsNotAllowedInAuction();
            }
            if (order.getMinimumExecutionQuantity() > 0) {
                return MatchResult.meqOrderIsNotAllowedInAuction();
            }
            result = matcher.auctionExecute(order);
        } else if (currentMatchingState == MatchingState.CONTINUOUS) {
            result = matcher.execute(order);
        }
        return result;
    }

    public MatchResult deleteOrder(DeleteOrderRq deleteOrderRq, Matcher matcher) throws InvalidRequestException {
        Order order = inactiveOrderBook.findByOrderId(deleteOrderRq.getSide(), deleteOrderRq.getOrderId());
        if (order == null) {
            order = orderBook.findByOrderId(deleteOrderRq.getSide(), deleteOrderRq.getOrderId());
        }
        else if(currentMatchingState == MatchingState.AUCTION){
            throw new InvalidRequestException(Message.CANNOT_DELETE_STOP_LIMIT_ORDER_IN_AUCTION_MODE);
        }
        if (order == null)
            throw new InvalidRequestException(Message.ORDER_ID_NOT_FOUND);
        if (order.getSide() == Side.BUY)
            order.getBroker().increaseCreditBy(order.getValue());
        orderBook.removeByOrderId(deleteOrderRq.getSide(), deleteOrderRq.getOrderId());
        inactiveOrderBook.removeByOrderId(deleteOrderRq.getSide(), deleteOrderRq.getOrderId());
        if (currentMatchingState == MatchingState.AUCTION) {
            matcher.calculateReopeningPrice(orderBook);
            return MatchResult.executed(MatchingOutcome.EXECUTED_IN_AUCTION);
        }
        return MatchResult.executed(MatchingOutcome.EXECUTED);
    }

    public MatchResult updateOrder(EnterOrderRq updateOrderRq, Matcher matcher) throws InvalidRequestException {
        Order order = null;
        order = inactiveOrderBook.findByOrderId(updateOrderRq.getSide(), updateOrderRq.getOrderId());

        if (order == null) {
            order = orderBook.findByOrderId(updateOrderRq.getSide(), updateOrderRq.getOrderId());
        }
        if (order == null) {
            throw new InvalidRequestException(Message.ORDER_ID_NOT_FOUND);
        }

        if(order instanceof StopLimitOrder && currentMatchingState == MatchingState.AUCTION){
            throw new InvalidRequestException(Message.CANNOT_UPDATE_STOP_LIMIT_ORDER_IN_AUCTION_MODE);
        }

        if ((order instanceof IcebergOrder) && updateOrderRq.getPeakSize() == 0)
            throw new InvalidRequestException(Message.INVALID_PEAK_SIZE);
        if (!(order instanceof IcebergOrder) && updateOrderRq.getPeakSize() != 0)
            throw new InvalidRequestException(Message.CANNOT_SPECIFY_PEAK_SIZE_FOR_A_NON_ICEBERG_ORDER);
        if (order.getMinimumExecutionQuantity() != updateOrderRq.getMinimumExecutionQuantity())
            throw new InvalidRequestException(Message.CANNOT_CHANGE_MEQ_DURING_UPDATE);

        if (updateOrderRq.getSide() == Side.SELL) {
            int position = orderBook.totalSellQuantityByShareholder(order.getShareholder())
                    - order.getQuantity()
                    + updateOrderRq.getQuantity();
            if (!order.getShareholder().hasEnoughPositionsOn(this, position)) {
                return MatchResult.notEnoughPositions();
            }
        }


        int newQuantity = updateOrderRq.getQuantity();
        boolean quantityIncreased = order.isQuantityIncreased(newQuantity);
        double newPrice = updateOrderRq.getPrice();
        boolean priceChanged = newPrice != order.getPrice();
        boolean peakSizeIncreased = order instanceof IcebergOrder icebergOrder
                && icebergOrder.getPeakSize() < updateOrderRq.getPeakSize();
        boolean stopPriceChanged = order instanceof StopLimitOrder stopLimitOrder
                && stopLimitOrder.getStopPrice() != updateOrderRq.getStopPrice();
        boolean losesPriority = quantityIncreased || priceChanged || peakSizeIncreased || stopPriceChanged;

        if (updateOrderRq.getSide() == Side.BUY) {
            order.getBroker().increaseCreditBy(order.getValue());
        }

        Order originalOrder = order.snapshot();
        order.updateFromRequest(updateOrderRq);

        if (!losesPriority) {
            if (updateOrderRq.getSide() == Side.BUY) {
                if (order.getBroker().getCredit() < order.getValue())
                    return MatchResult.notEnoughCredit();
                order.getBroker().decreaseCreditBy(order.getValue());
            }
            if (currentMatchingState == MatchingState.AUCTION) {
                matcher.calculateReopeningPrice(orderBook);
                // return this result
            }
            return MatchResult.executed(null, List.of());
        }

        // if (losesPriority) :
        orderBook.removeByOrderId(updateOrderRq.getSide(), updateOrderRq.getOrderId());
        inactiveOrderBook.removeByOrderId(updateOrderRq.getSide(), updateOrderRq.getOrderId());

        MatchResult matchResult = null;
        if (currentMatchingState == MatchingState.AUCTION) {
            matchResult = matcher.auctionExecute(order);
        } else if (currentMatchingState == MatchingState.CONTINUOUS) {
            // The
            matchResult = matcher.execute(order);
        }
//        result.outcome() == MatchingOutcome.NOT_ENOUGH_CREDIT

        // TODO: do we check in else statement, it will decreaseCredit????
        // TODO : In if kheili kirie
        if (matchResult.outcome() != MatchingOutcome.EXECUTED
                && matchResult.outcome() != MatchingOutcome.NOT_MET_LAST_TRADE_PRICE) {
            orderBook.enqueue(originalOrder);
            if (updateOrderRq.getSide() == Side.BUY) {
                if (!originalOrder.getBroker().hasEnoughCredit(originalOrder.getValue())) {
                    return MatchResult.notEnoughCredit();
                }
                // TODO: note that we already deceased Credit in both executes
                originalOrder.getBroker().decreaseCreditBy(originalOrder.getValue());
            }
        }
        return matchResult;
    }

    public MatchResult updateMatchingState(MatchingState newMatchingState, Matcher matcher) {
        MatchResult matchResult = null;
        if (this.currentMatchingState == MatchingState.AUCTION) {
            matcher.calculateReopeningPrice(orderBook);
            LinkedList<Trade> trades = matcher.auctionMatch(orderBook);
            if (trades.isEmpty()) {
                return MatchResult.executed();
            }
            matcher.setLastTradePrice(matcher.reopeningPrice);

            Order activatedOrder = null;
            while (true) {
                activatedOrder = inactiveOrderBook.getActivateCandidateOrders(matcher.getLastTradePrice());
                if (activatedOrder == null)
                    break;
                if (newMatchingState == MatchingState.AUCTION) {
                    // other results?
                    matchResult = matcher.auctionExecute(activatedOrder);
                } else {
                    // other results?
                    matchResult = matcher.execute(activatedOrder);
                }
                trades.addAll(matchResult.trades());
            }
            matchResult = MatchResult.executed(trades);
        }
        this.currentMatchingState = newMatchingState;
        return matchResult;
    }
}



