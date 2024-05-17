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

    private Order createNewOrderInstance(EnterOrderRq enterOrderRq, Broker broker, Shareholder shareholder) {
        if (enterOrderRq.getPeakSize() == 0 && enterOrderRq.getStopPrice() == 0)
            return new Order(enterOrderRq.getOrderId(), this, enterOrderRq.getSide(),
                    enterOrderRq.getQuantity(), enterOrderRq.getPrice(), broker, shareholder, enterOrderRq.getEntryTime(),
                    OrderStatus.NEW, enterOrderRq.getMinimumExecutionQuantity());
        else if (enterOrderRq.getPeakSize() > 0 && enterOrderRq.getStopPrice() == 0)
            return new IcebergOrder(enterOrderRq.getOrderId(), this, enterOrderRq.getSide(),
                    enterOrderRq.getQuantity(), enterOrderRq.getPrice(), broker, shareholder,
                    enterOrderRq.getEntryTime(), enterOrderRq.getPeakSize(), OrderStatus.NEW,
                    enterOrderRq.getMinimumExecutionQuantity());
        else
            return new StopLimitOrder(enterOrderRq.getOrderId(), this, enterOrderRq.getSide(),
                    enterOrderRq.getQuantity(), enterOrderRq.getPrice(), broker, shareholder,
                    enterOrderRq.getEntryTime(), OrderStatus.NEW, enterOrderRq.getStopPrice());
    }
    public MatchResult newOrder(EnterOrderRq enterOrderRq, Broker broker, Shareholder shareholder, Matcher matcher) {
        if (enterOrderRq.getSide() == Side.SELL &&
                !shareholder.hasEnoughPositionsOn(this,
                        orderBook.totalSellQuantityByShareholder(shareholder) + enterOrderRq.getQuantity()))
            return MatchResult.notEnoughPositions();
        Order order = createNewOrderInstance(enterOrderRq, broker, shareholder);
        MatchResult result = null;
        if (currentMatchingState == MatchingState.AUCTION) {
            if (order instanceof StopLimitOrder)
                return MatchResult.stopLimitOrderIsNotAllowedInAuction();
            if (order.getMinimumExecutionQuantity() > 0)
                return MatchResult.meqOrderIsNotAllowedInAuction();
            result = matcher.auctionExecute(order);
        } else if (currentMatchingState == MatchingState.CONTINUOUS) {
            result = matcher.execute(order);
        }
        return result;
    }

    public void deleteOrder(DeleteOrderRq deleteOrderRq, Matcher matcher) throws InvalidRequestException {
        Order order = inactiveOrderBook.findByOrderId(deleteOrderRq.getSide(), deleteOrderRq.getOrderId());
        if (order == null) {
            order = orderBook.findByOrderId(deleteOrderRq.getSide(), deleteOrderRq.getOrderId());
        }
        else if(currentMatchingState == MatchingState.AUCTION){
            throw new InvalidRequestException(Message.CANNOT_DELETE_STOP_LIMIT_ORDER_IN_AUCTION_MODE);
        }
        if (order == null) {
            throw new InvalidRequestException(Message.ORDER_ID_NOT_FOUND);
        }
        if (order.getSide() == Side.BUY) {
            order.getBroker().increaseCreditBy(order.getValue());
        }

        orderBook.removeByOrderId(deleteOrderRq.getSide(), deleteOrderRq.getOrderId());
        inactiveOrderBook.removeByOrderId(deleteOrderRq.getSide(), deleteOrderRq.getOrderId());
        if (currentMatchingState == MatchingState.AUCTION) {
            matcher.calculateReopeningPrice(orderBook);
        }
    }

    private Order findOrder(EnterOrderRq updateOrderRq) throws InvalidRequestException {
        Order order = inactiveOrderBook.findByOrderId(updateOrderRq.getSide(), updateOrderRq.getOrderId());
        if (currentMatchingState == MatchingState.AUCTION && order != null) {
            throw new InvalidRequestException(Message.CANNOT_UPDATE_INACTIVE_STOP_LIMIT_ORDER_IN_AUCTION_MODE);
        }

        if (order == null) {
            order = orderBook.findByOrderId(updateOrderRq.getSide(), updateOrderRq.getOrderId());
        }

        if (order == null) {
            throw new InvalidRequestException(Message.ORDER_ID_NOT_FOUND);
        }

        return order;
    }

    private void validateOrder(EnterOrderRq updateOrderRq, Order order) throws InvalidRequestException {
        if (order instanceof IcebergOrder && updateOrderRq.getPeakSize() == 0) {
            throw new InvalidRequestException(Message.INVALID_PEAK_SIZE);
        }

        if (!(order instanceof IcebergOrder) && updateOrderRq.getPeakSize() != 0) {
            throw new InvalidRequestException(Message.CANNOT_SPECIFY_PEAK_SIZE_FOR_A_NON_ICEBERG_ORDER);
        }

        if (order.getMinimumExecutionQuantity() != updateOrderRq.getMinimumExecutionQuantity()) {
            throw new InvalidRequestException(Message.CANNOT_CHANGE_MEQ_DURING_UPDATE);
        }
    }

    private boolean doesHaveEnoughPosition(EnterOrderRq updateOrderRq, Order order) {
        int position = orderBook.totalSellQuantityByShareholder(order.getShareholder())
                - order.getQuantity() + updateOrderRq.getQuantity();
        return order.getShareholder().hasEnoughPositionsOn(this, position);
    }

    private static boolean doesLosePriority(EnterOrderRq updateOrderRq, Order order, boolean quantityIncreased) {
        double newPrice = updateOrderRq.getPrice();
        boolean priceChanged = newPrice != order.getPrice();
        boolean peakSizeIncreased = order instanceof IcebergOrder icebergOrder
                && icebergOrder.getPeakSize() < updateOrderRq.getPeakSize();
        boolean stopPriceChanged = order instanceof StopLimitOrder stopLimitOrder
                && stopLimitOrder.getStopPrice() != updateOrderRq.getStopPrice();

        return quantityIncreased || priceChanged || peakSizeIncreased || stopPriceChanged ;
    }

    public MatchResult updateOrder(EnterOrderRq updateOrderRq, Matcher matcher) throws InvalidRequestException {
        Order order = findOrder(updateOrderRq);
        validateOrder(updateOrderRq, order);
        if (updateOrderRq.getSide() == Side.SELL
                && !doesHaveEnoughPosition(updateOrderRq, order)) {
            return MatchResult.notEnoughPositions();
        }

        if (updateOrderRq.getSide() == Side.BUY)
            order.getBroker().increaseCreditBy(order.getValue());

        boolean quantityIncreased = order.isQuantityIncreased(updateOrderRq.getQuantity());
        boolean losesPriority = doesLosePriority(updateOrderRq, order, quantityIncreased);

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
                return MatchResult.executed();
            }
            return MatchResult.executed(null, List.of());
        }

        orderBook.removeByOrderId(updateOrderRq.getSide(), updateOrderRq.getOrderId());
        inactiveOrderBook.removeByOrderId(updateOrderRq.getSide(), updateOrderRq.getOrderId());

        MatchResult matchResult = null;
        if (currentMatchingState == MatchingState.AUCTION) {
            matchResult = matcher.auctionExecute(order);
        } else if (currentMatchingState == MatchingState.CONTINUOUS) {
            matchResult = matcher.execute(order);
        }

        assert matchResult != null;
        if (matchResult.outcome() != MatchingOutcome.NOT_MET_LAST_TRADE_PRICE
                && matchResult.outcome() != MatchingOutcome.EXECUTED &&
                    matchResult.outcome() != MatchingOutcome.EXECUTED_IN_AUCTION) {
            orderBook.enqueue(originalOrder);
            if (updateOrderRq.getSide() == Side.BUY) {
                if (!originalOrder.getBroker().hasEnoughCredit(originalOrder.getValue())) {
                    return MatchResult.notEnoughCredit();
                }
                originalOrder.getBroker().decreaseCreditBy(originalOrder.getValue());
            }
        }
        return matchResult;
    }

    public Order getActivateCandidateOrder(int lastTradePrice) {
        Order activatedOrder = null;
        activatedOrder = getInactiveOrderBook().getActivateCandidateOrders(lastTradePrice);
        return activatedOrder;
    }
    public MatchResult checkForSLOActivation(MatchingState newMatchingState, Matcher matcher, LinkedList<Trade> trades) {
        MatchResult matchResult = null;
        Order activatedOrder = null;
        while ((activatedOrder = getActivateCandidateOrder(matcher.getLastTradePrice())) != null) {
            if (newMatchingState == MatchingState.AUCTION) {
                matchResult = matcher.auctionExecute(activatedOrder);
            } else {
                matchResult = matcher.execute(activatedOrder);
            }
            trades.addAll(matchResult.trades());
        }
        return MatchResult.executed(trades);
    }
    public MatchResult updateMatchingState(MatchingState newMatchingState, Matcher matcher) {
        MatchResult matchResult = null;
        if (this.currentMatchingState == MatchingState.AUCTION) {
            matcher.calculateReopeningPrice(orderBook);
            LinkedList<Trade> trades = matcher.auctionMatch(orderBook);
            if (trades.isEmpty()) {
                this.currentMatchingState = newMatchingState;
                return MatchResult.executed();
            }
            matcher.setLastTradePrice(matcher.reopeningPrice);

            matchResult = checkForSLOActivation(newMatchingState, matcher, trades);
        }
        this.currentMatchingState = newMatchingState;
        return matchResult;
    }
}



