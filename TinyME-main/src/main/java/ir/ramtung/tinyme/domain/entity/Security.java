package ir.ramtung.tinyme.domain.entity;

import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.request.ChangeMatchingStateRq;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import lombok.Builder;
import lombok.Getter;
import org.apache.commons.lang3.ObjectUtils;

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

    // TODO : check if the matching state is on harraj and the order type doesn't satisfy the process, propagate error.
    // IcebergOrder                             ->  Allowed. same approach for changing priority
    // StopLimitOrder                           ->  Not Allowed.
    // Order with getMinimumExecutionQuantity   ->  Not Allowed.
    // Order without getMinimumExecutionQuantity   ->  Allowed.
    // also check for other types of orders which are not mentioned.
    // If order is accepted, publish <rokhdade paziresh sefaresh>

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
//            result = matcher.auctionExecute(order);
            result = Matcher.auctionExecute(order);
        } else if (currentMatchingState == MatchingState.CONTINUOUS) {
            result = matcher.execute(order);
        }
        return result;
    }

    public void deleteOrder(DeleteOrderRq deleteOrderRq) throws InvalidRequestException {
        Order order = orderBook.findByOrderId(deleteOrderRq.getSide(), deleteOrderRq.getOrderId());
        if (order == null)
            order = inactiveOrderBook.findByOrderId(deleteOrderRq.getSide(), deleteOrderRq.getOrderId());
        if (order == null)
            throw new InvalidRequestException(Message.ORDER_ID_NOT_FOUND);
        if (order.getSide() == Side.BUY)
            order.getBroker().increaseCreditBy(order.getValue());
        orderBook.removeByOrderId(deleteOrderRq.getSide(), deleteOrderRq.getOrderId());
        inactiveOrderBook.removeByOrderId(deleteOrderRq.getSide(), deleteOrderRq.getOrderId());
    }

    public MatchResult updateOrder(EnterOrderRq updateOrderRq, Matcher matcher) throws InvalidRequestException {
        Order order = null;

        order = inactiveOrderBook.findByOrderId(updateOrderRq.getSide(), updateOrderRq.getOrderId());
        if (order == null) {
            order = orderBook.findByOrderId(updateOrderRq.getSide(), updateOrderRq.getOrderId());
            if (order != null && updateOrderRq.getStopPrice() > 0) {
                throw new InvalidRequestException(Message.CANNOT_UPDATE_ACTIVE_STOP_LIMIT_ORDER);
            }
        }
        if (order == null) {
            throw new InvalidRequestException(Message.ORDER_ID_NOT_FOUND);
        }

        if ((order instanceof IcebergOrder) && updateOrderRq.getPeakSize() == 0)
            throw new InvalidRequestException(Message.INVALID_PEAK_SIZE);
        if (!(order instanceof IcebergOrder) && updateOrderRq.getPeakSize() != 0)
            throw new InvalidRequestException(Message.CANNOT_SPECIFY_PEAK_SIZE_FOR_A_NON_ICEBERG_ORDER);
        if (order.getMinimumExecutionQuantity() != updateOrderRq.getMinimumExecutionQuantity())
            throw new InvalidRequestException(Message.CANNOT_CHANGE_MEQ_DURING_UPDATE);

        if (updateOrderRq.getSide() == Side.SELL &&
                !order.getShareholder().hasEnoughPositionsOn(this,
                orderBook.totalSellQuantityByShareholder(order.getShareholder()) - order.getQuantity() + updateOrderRq.getQuantity())) {
            return MatchResult.notEnoughPositions();
        }

        boolean losesPriority = order.isQuantityIncreased(updateOrderRq.getQuantity())
                || updateOrderRq.getPrice() != order.getPrice()
                || ((order instanceof IcebergOrder icebergOrder) && (icebergOrder.getPeakSize() < updateOrderRq.getPeakSize()))
                || ((order instanceof StopLimitOrder stopLimitOrder) && (stopLimitOrder.getStopPrice() != updateOrderRq.getStopPrice()));

        if (updateOrderRq.getSide() == Side.BUY) {
            order.getBroker().increaseCreditBy(order.getValue());
        }

        Order originalOrder = order.snapshot();
        order.updateFromRequest(updateOrderRq);

        if (!losesPriority) {
            if (updateOrderRq.getSide() == Side.BUY) {
                order.getBroker().decreaseCreditBy(order.getValue());
            }
            return MatchResult.executed(null, List.of());
        }

        orderBook.removeByOrderId(updateOrderRq.getSide(), updateOrderRq.getOrderId());
        inactiveOrderBook.removeByOrderId(updateOrderRq.getSide(), updateOrderRq.getOrderId());
        MatchResult matchResult = matcher.execute(order);
        if (matchResult.outcome() != MatchingOutcome.EXECUTED && matchResult.outcome() != MatchingOutcome.NOT_MET_LAST_TRADE_PRICE) {
            orderBook.enqueue(originalOrder);
            if (updateOrderRq.getSide() == Side.BUY) {
                originalOrder.getBroker().decreaseCreditBy(originalOrder.getValue());
            }
        }
        return matchResult;
    }
    // TODO order handler gives request to change matching state field. add method
    // publish some set of TradeEvent (previously it was OrderExecutedEvent, so you can check playcement
    // of Event is similar to OrderExecutedEvent to avoid bugs.)
    public MatchResult updateMatchingState(MatchingState newMatchingState) {
        if (this.currentMatchingState == MatchingState.AUCTION
                && newMatchingState == MatchingState.CONTINUOUS) {
            return Matcher.auctionMatch(this.orderBook);
        }
        // other conditions? error or what?
        this.currentMatchingState = newMatchingState;
        return MatchResult.executedInAuction();
    }
}
