package ir.ramtung.tinyme.domain.entity;

import ir.ramtung.tinyme.domain.service.MatchingControlList;
import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import lombok.Builder;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
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
        if (enterOrderRq.getPeakSize() == 0 && enterOrderRq.getStopPrice() == 0) {
            return new Order(enterOrderRq.getOrderId(), this, enterOrderRq.getSide(),
                    enterOrderRq.getQuantity(), enterOrderRq.getPrice(), broker, shareholder,
                    enterOrderRq.getEntryTime(), OrderStatus.NEW,
                    enterOrderRq.getMinimumExecutionQuantity());
        }
        if (enterOrderRq.getPeakSize() > 0 && enterOrderRq.getStopPrice() == 0) {
            return new IcebergOrder(enterOrderRq.getOrderId(), this, enterOrderRq.getSide(),
                    enterOrderRq.getQuantity(), enterOrderRq.getPrice(), broker, shareholder,
                    enterOrderRq.getEntryTime(), enterOrderRq.getPeakSize(), OrderStatus.NEW,
                    enterOrderRq.getMinimumExecutionQuantity());
        }
        return new StopLimitOrder(enterOrderRq.getOrderId(), this, enterOrderRq.getSide(),
                enterOrderRq.getQuantity(), enterOrderRq.getPrice(), broker, shareholder,
                enterOrderRq.getEntryTime(), OrderStatus.NEW, enterOrderRq.getStopPrice());
    }

    public MatchResult newOrder(EnterOrderRq enterOrderRq, Broker broker, Shareholder shareholder, Matcher matcher) {
        Order order = createNewOrderInstance(enterOrderRq, broker, shareholder);

        if (currentMatchingState == MatchingState.CONTINUOUS) {
            return matcher.execute(order);
        }

        if (order instanceof StopLimitOrder) {
            return MatchResult.stopLimitOrderIsNotAllowedInAuction();
        }
        if (order.getMinimumExecutionQuantity() > 0) {
            return MatchResult.meqOrderIsNotAllowedInAuction();
        }
        return matcher.auctionExecute(order);
    }

    private Order findOrder(Side orderSide, long orderId, String forbiddenActionMsg) throws InvalidRequestException {
        Order order = inactiveOrderBook.findByOrderId(orderSide, orderId);
        if (order != null && currentMatchingState == MatchingState.AUCTION) {
            throw new InvalidRequestException(forbiddenActionMsg);
        }
        if (order == null) {
            order = orderBook.findByOrderId(orderSide, orderId);
        }
        if (order == null) {
            throw new InvalidRequestException(Message.ORDER_ID_NOT_FOUND);
        }
        return order;
    }

    private void removeFromOrderBook(DeleteOrderRq deleteOrderRq) {
        orderBook.removeByOrderId(deleteOrderRq.getSide(), deleteOrderRq.getOrderId());
        inactiveOrderBook.removeByOrderId(deleteOrderRq.getSide(), deleteOrderRq.getOrderId());
    }

    public void deleteOrder(DeleteOrderRq deleteOrderRq, Matcher matcher) throws InvalidRequestException {
        Order order = findOrder(deleteOrderRq.getSide(), deleteOrderRq.getOrderId(),
                Message.CANNOT_DELETE_STOP_LIMIT_ORDER_IN_AUCTION_MODE);
        // TODO::! increaseCreditBy() : 2
        if (order.getSide() == Side.BUY) order.getBroker().increaseCreditBy(order.getValue());
        removeFromOrderBook(deleteOrderRq);
        if (currentMatchingState == MatchingState.AUCTION) matcher.calculateReopeningPrice(orderBook);
    }

    private void validateOrder(EnterOrderRq updateOrderRq, Order order) throws InvalidRequestException {
        if (order instanceof IcebergOrder && updateOrderRq.getPeakSize() == 0)
            throw new InvalidRequestException(Message.INVALID_PEAK_SIZE);

        if (!(order instanceof IcebergOrder) && updateOrderRq.getPeakSize() != 0)
            throw new InvalidRequestException(Message.CANNOT_SPECIFY_PEAK_SIZE_FOR_A_NON_ICEBERG_ORDER);

        if (order.getMinimumExecutionQuantity() != updateOrderRq.getMinimumExecutionQuantity())
            throw new InvalidRequestException(Message.CANNOT_CHANGE_MEQ_DURING_UPDATE);
    }

    private static boolean doesLosePriority(EnterOrderRq updateOrderRq, Order order, boolean quantityIncreased) {
        double newPrice = updateOrderRq.getPrice();
        boolean priceChanged = newPrice != order.getPrice();
        boolean peakSizeIncreased = order instanceof IcebergOrder icebergOrder &&
                icebergOrder.getPeakSize() < updateOrderRq.getPeakSize();
        boolean stopPriceChanged = order instanceof StopLimitOrder stopLimitOrder &&
                stopLimitOrder.getStopPrice() != updateOrderRq.getStopPrice();

        return (quantityIncreased || priceChanged || peakSizeIncreased || stopPriceChanged);
    }

    private MatchResult updateOrderWithSamePriority(Order order, Side orderSide, Matcher matcher) {
        if (orderSide == Side.BUY && !order.getBroker().hasEnoughCredit(order.getValue()))
            return MatchResult.notEnoughCredit();
        if (orderSide == Side.BUY)
            order.getBroker().decreaseCreditBy(order.getValue());

        if (currentMatchingState == MatchingState.AUCTION) {
            matcher.calculateReopeningPrice(orderBook);
            return MatchResult.executed();
        } else {
            return MatchResult.executed(null, List.of());
        }
    }

    public MatchResult updateOrder(EnterOrderRq updateOrderRq, Matcher matcher) throws InvalidRequestException {
        Order order = findOrder(updateOrderRq.getSide(), updateOrderRq.getOrderId(),
                Message.CANNOT_UPDATE_INACTIVE_STOP_LIMIT_ORDER_IN_AUCTION_MODE);
        validateOrder(updateOrderRq, order);
        int position = orderBook.totalSellQuantityByShareholder(order.getShareholder())
                - order.getQuantity() + updateOrderRq.getQuantity();
        if (updateOrderRq.getSide() == Side.SELL
                && !order.getShareholder().hasEnoughPositionsOn(this, position)) {
            return MatchResult.notEnoughPositions();
        }

        if (updateOrderRq.getSide() == Side.BUY) order.getBroker().increaseCreditBy(order.getValue());

        boolean quantityIncreased = order.isQuantityIncreased(updateOrderRq.getQuantity());
        boolean losesPriority = doesLosePriority(updateOrderRq, order, quantityIncreased);

        Order originalOrder = order.snapshot();
        order.updateFromRequest(updateOrderRq);
        if (!losesPriority) return updateOrderWithSamePriority(order, updateOrderRq.getSide(), matcher);

        orderBook.removeByOrderId(updateOrderRq.getSide(), updateOrderRq.getOrderId());
        inactiveOrderBook.removeByOrderId(updateOrderRq.getSide(), updateOrderRq.getOrderId());

        MatchResult matchResult = null;
        if (currentMatchingState == MatchingState.AUCTION) matchResult = matcher.auctionExecute(order);
        else if (currentMatchingState == MatchingState.CONTINUOUS) matchResult = matcher.execute(order);

        assert matchResult != null; // TODO :: will it be null at any scenario???
        if (matchResult.outcome() != MatchingOutcome.NOT_MET_LAST_TRADE_PRICE
                && matchResult.outcome() != MatchingOutcome.EXECUTED &&
                    matchResult.outcome() != MatchingOutcome.EXECUTED_IN_AUCTION) {
            orderBook.enqueue(originalOrder);
            // TODO::! notEnoughCredit() : 2
            if (updateOrderRq.getSide() == Side.BUY && !originalOrder.getBroker().hasEnoughCredit(originalOrder.getValue())) {
                return MatchResult.notEnoughCredit();
            }

            // TODO::! decreaseCreditBy() : 2
            if (updateOrderRq.getSide() == Side.BUY) originalOrder.getBroker().decreaseCreditBy(originalOrder.getValue());

        }
        return matchResult;
    }

    private Order getActivateCandidateOrder(int lastTradePrice) {
        return getInactiveOrderBook().getActivationCandidateOrder(lastTradePrice);
    }

    public ArrayList<MatchResult> activateStopLimitOrder(Matcher matcher, MatchingState targetState){
        Order activatedOrder = null;
        ArrayList<MatchResult> results = new ArrayList<>();
        while ((activatedOrder = (this.getActivateCandidateOrder(matcher.getLastTradePrice()))) != null) {
            results.add(MatchResult.activated(activatedOrder));

            if(targetState == MatchingState.AUCTION){
                matcher.auctionExecute(activatedOrder);
                continue;
            }

            results.add(matcher.execute(activatedOrder));

        }
        return results;
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
            matchResult = MatchResult.executed(trades);
        }
        this.currentMatchingState = newMatchingState;
        return matchResult;
    }
}