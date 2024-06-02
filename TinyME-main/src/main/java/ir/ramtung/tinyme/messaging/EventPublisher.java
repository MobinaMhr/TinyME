package ir.ramtung.tinyme.messaging;

import ir.ramtung.tinyme.domain.entity.MatchResult;
import ir.ramtung.tinyme.domain.entity.Trade;
import ir.ramtung.tinyme.messaging.event.*;
import ir.ramtung.tinyme.messaging.request.ChangeMatchingStateRq;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@Component
public class EventPublisher {
    private final Logger log = Logger.getLogger(this.getClass().getName());
    private final JmsTemplate jmsTemplate;
    @Value("${responseQueue}")
    private String responseQueue;

    public EventPublisher(JmsTemplate jmsTemplate) {
        this.jmsTemplate = jmsTemplate;
    }

    private void publish(Event event) {
        log.info("Published : " + event);
        jmsTemplate.convertAndSend(responseQueue, event);
    }
    public void publishSecurityStateChangedEvent(ChangeMatchingStateRq changeMatchingStateRq) {
        this.publish(new SecurityStateChangedEvent(changeMatchingStateRq.getSecurityIsin(),
                changeMatchingStateRq.getTargetState()));
    }
    public void publishChangeMatchingStateRqRejectedEvent(ChangeMatchingStateRq changeMatchingStateRq) {
        this.publish(new ChangeMatchingStateRqRejectedEvent(
                changeMatchingStateRq.getSecurityIsin(), changeMatchingStateRq.getTargetState()));
    }
    public void publishAcceptedOrderEvent(EnterOrderRq enterOrderRq) {
        this.publish(new OrderAcceptedEvent(enterOrderRq.getRequestId(),
                enterOrderRq.getOrderId()));
    }
    public void publishOrderUpdatedEvent(EnterOrderRq enterOrderRq) {
        this.publish(new OrderUpdatedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId()));
    }
    public void publishIfTradeExists(long requestId, long orderId, MatchResult matchResult) {
        if (matchResult.trades().isEmpty()) return;
        this.publish(new OrderExecutedEvent(requestId, orderId,
                matchResult.trades().stream().map(TradeDTO::new).collect(Collectors.toList())));
    }
    public void publishOrderActivateEvent(long requestId, long orderId) {
        this.publish(new OrderActivateEvent(requestId, orderId));
    }
    public void publishOpeningPriceEvent(String isin, int reopeningPrice, int maxTradableQuantity) {
        this.publish(new OpeningPriceEvent(isin, reopeningPrice, maxTradableQuantity));
    }
    // TODO: remove 2 of 3 or create new super class for requests
    public void publishOrderRejectedEvent(DeleteOrderRq deleteOrderRq, List<String> msgList) {
        this.publish(new OrderRejectedEvent(deleteOrderRq.getRequestId(), deleteOrderRq.getOrderId(), msgList));
    }
    public void publishOrderRejectedEvent(long requestId, long orderId, List<String> msgList) {
        this.publish(new OrderRejectedEvent(requestId, orderId, msgList));
    }
    public void publishTradeEvents(MatchResult result, String isin) {
        for (Trade trade : result.trades()) {
            this.publish(
                    new TradeEvent(isin, trade.getPrice(), trade.getQuantity(),
                            trade.getBuy().getOrderId(), trade.getSell().getOrderId())
            );
        }
    }
    public void publishOrderDeletedEvent(DeleteOrderRq deleteOrderRq) {
        this.publish(new OrderDeletedEvent(deleteOrderRq.getRequestId(), deleteOrderRq.getOrderId()));
    }
    public void publishOrderRejectedEvent(EnterOrderRq enterOrderRq, MatchResult matchResult) {
        String message = switch (matchResult.outcome()) {
            case NOT_ENOUGH_CREDIT -> Message.BUYER_HAS_NOT_ENOUGH_CREDIT;
            case NOT_ENOUGH_POSITIONS -> Message.SELLER_HAS_NOT_ENOUGH_POSITIONS;
            case NOT_MET_MEQ_VALUE -> Message.ORDER_NOT_MET_MEQ_VALUE;
            default -> throw new IllegalArgumentException("Invalid outcome for rejection event");
        };
        publishOrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), List.of(message));
    }
}
