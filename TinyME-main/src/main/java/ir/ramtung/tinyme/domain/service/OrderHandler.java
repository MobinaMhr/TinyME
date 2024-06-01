package ir.ramtung.tinyme.domain.service;

import com.fasterxml.jackson.databind.deser.DataFormatReaders;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.TradeDTO;
import ir.ramtung.tinyme.messaging.event.*;
import ir.ramtung.tinyme.messaging.request.*;
import ir.ramtung.tinyme.repository.BrokerRepository;
import ir.ramtung.tinyme.repository.SecurityRepository;
import ir.ramtung.tinyme.repository.ShareholderRepository;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class OrderHandler {
    SecurityRepository securityRepository;
    BrokerRepository brokerRepository;
    ShareholderRepository shareholderRepository;
    EventPublisher eventPublisher;
    Matcher matcher;
    private  HashMap<Long, Long> orderIdRqIdMap;

    public OrderHandler(SecurityRepository securityRepository, BrokerRepository brokerRepository,
                        ShareholderRepository shareholderRepository, EventPublisher eventPublisher, Matcher matcher) {
        this.securityRepository = securityRepository;
        this.brokerRepository = brokerRepository;
        this.shareholderRepository = shareholderRepository;
        this.eventPublisher = eventPublisher;
        this.matcher = matcher;
        this.orderIdRqIdMap = new HashMap<Long, Long>(); // TODO Convert to Object
    }

    // TODO move all of these to EventPublisher.
    private void publishSecurityStateChangedEvent(ChangeMatchingStateRq changeMatchingStateRq) {
        eventPublisher.publish(new SecurityStateChangedEvent(changeMatchingStateRq.getSecurityIsin(),
                changeMatchingStateRq.getTargetState()));
    }
    private void publishChangeMatchingStateRqRejectedEvent(ChangeMatchingStateRq changeMatchingStateRq) {
        eventPublisher.publish(new ChangeMatchingStateRqRejectedEvent(
                changeMatchingStateRq.getSecurityIsin(), changeMatchingStateRq.getTargetState()));
    }
    private void publishAcceptedOrderEvent(EnterOrderRq enterOrderRq) {
        eventPublisher.publish(new OrderAcceptedEvent(enterOrderRq.getRequestId(),
                enterOrderRq.getOrderId()));
        orderIdRqIdMap.put(enterOrderRq.getOrderId(), enterOrderRq.getRequestId());
    }
    private void publishOrderUpdatedEvent(EnterOrderRq enterOrderRq) {
        eventPublisher.publish(new OrderUpdatedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId()));
        orderIdRqIdMap.put(enterOrderRq.getOrderId(), enterOrderRq.getRequestId());
    }
    private void publishIfTradeExists(long requestId, long orderId, MatchResult matchResult) {
        if (matchResult.trades().isEmpty()) return;
        eventPublisher.publish(new OrderExecutedEvent(requestId, orderId,
                matchResult.trades().stream().map(TradeDTO::new).collect(Collectors.toList())));
    }
    private void publishOrderActivateEvent(long requestId, long orderId) {
        eventPublisher.publish(new OrderActivateEvent(requestId, orderId));
    }
    private void publishOpeningPriceEvent(String isin) {
        eventPublisher.publish(new OpeningPriceEvent(isin,
                matcher.getReopeningPrice(), matcher.maxTradableQuantity));
    }
    // TODO: remove 2 of 3 or create new super class for requests
    private void publishOrderRejectedEvent(DeleteOrderRq deleteOrderRq, List<String> msgList) {
        eventPublisher.publish(new OrderRejectedEvent(deleteOrderRq.getRequestId(), deleteOrderRq.getOrderId(), msgList));
    }
    private void publishOrderRejectedEvent(long requestId, long orderId, List<String> msgList) {
        eventPublisher.publish(new OrderRejectedEvent(requestId, orderId, msgList));
    }
    private void publishTradeEvents(MatchResult result, String isin) {
        for (Trade trade : result.trades()) {
            eventPublisher.publish(
                    new TradeEvent(isin, trade.getPrice(), trade.getQuantity(),
                    trade.getBuy().getOrderId(), trade.getSell().getOrderId())
            );
        }
    }
    private void publishOrderDeletedEvent(DeleteOrderRq deleteOrderRq) {
        eventPublisher.publish(new OrderDeletedEvent(deleteOrderRq.getRequestId(), deleteOrderRq.getOrderId()));
        orderIdRqIdMap.remove(deleteOrderRq.getOrderId());
    }
    private void publishOrderRejectedEvent(EnterOrderRq enterOrderRq, MatchResult matchResult) {
        String message = switch (matchResult.outcome()) {
            case NOT_ENOUGH_CREDIT -> Message.BUYER_HAS_NOT_ENOUGH_CREDIT;
            case NOT_ENOUGH_POSITIONS -> Message.SELLER_HAS_NOT_ENOUGH_POSITIONS;
            case NOT_MET_MEQ_VALUE -> Message.ORDER_NOT_MET_MEQ_VALUE;
            default -> throw new IllegalArgumentException("Invalid outcome for rejection event");
        };
        publishOrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), List.of(message));
    }

    public boolean resultPublisher(MatchResult matchResult, EnterOrderRq enterOrderRq,
                                   boolean isTypeStopLimitOrder, Security security) {
        switch (matchResult.outcome()) {
            case EXECUTED:
                if (isTypeStopLimitOrder)
                    publishOrderActivateEvent(enterOrderRq.getRequestId(),
                            matchResult.remainder().getOrderId());
                break;
            case EXECUTED_IN_AUCTION:
                publishOpeningPriceEvent(security.getIsin());
                break;
            case NOT_ENOUGH_CREDIT:
            case NOT_ENOUGH_POSITIONS:
            case NOT_MET_MEQ_VALUE:
                publishOrderRejectedEvent(enterOrderRq, matchResult);
                return true;
            case NOT_MET_LAST_TRADE_PRICE:
                publishAcceptedOrderEvent(enterOrderRq);
                return true;
            case MEQ_ORDER_IS_NOT_ALLOWED_IN_AUCTION:
            case STOP_LIMIT_ORDER_IS_NOT_ALLOWED_IN_AUCTION:
                return true;
        }

        if (enterOrderRq.getRequestType() == OrderEntryType.NEW_ORDER) {
            publishAcceptedOrderEvent(enterOrderRq);
        } else {
            publishOrderUpdatedEvent(enterOrderRq);
        }

        publishIfTradeExists(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), matchResult);
        return false;
    }
    public void handleEnterOrder(EnterOrderRq enterOrderRq) {
        try {
            validateEnterOrderRq(enterOrderRq);
            Security security = securityRepository.findSecurityByIsin(enterOrderRq.getSecurityIsin());
            Broker broker = brokerRepository.findBrokerById(enterOrderRq.getBrokerId());
            Shareholder shareholder = shareholderRepository.findShareholderById(enterOrderRq.getShareholderId());

            MatchResult matchResult;
            boolean isTypeStopLimitOrder;
            if (enterOrderRq.getRequestType() == OrderEntryType.NEW_ORDER) {
                matchResult = security.newOrder(enterOrderRq, broker, shareholder, matcher);
                isTypeStopLimitOrder = enterOrderRq.getStopPrice() > 0;
            } else{
                isTypeStopLimitOrder = security.getInactiveOrderBook()
                        .findByOrderId(enterOrderRq.getSide(),enterOrderRq.getOrderId()) != null;
                matchResult = security.updateOrder(enterOrderRq, matcher);
            }

            if(matchResult != null && !resultPublisher(matchResult, enterOrderRq, isTypeStopLimitOrder, security)) {
                executeActivatedSLO(security, null);
            }

        } catch (InvalidRequestException e) {
                publishOrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), e.getReasons());
        }
    }
    public void handleChangeMatchingStateRq(ChangeMatchingStateRq changeMatchingStateRq) {
        try {
            validateChangeMatchingStateRq(changeMatchingStateRq);

            Security security = securityRepository.findSecurityByIsin(changeMatchingStateRq.getSecurityIsin());
            MatchingState oldMatchingState = security.getCurrentMatchingState();
            MatchResult result = security.updateMatchingState(changeMatchingStateRq.getTargetState(), matcher);
            publishSecurityStateChangedEvent(changeMatchingStateRq);

            // Shall we change ? TODO
            if (result == null || result.trades() == null) return;
            publishTradeEvents(result, security.getIsin());

            // TODO Should we move this if before previous if?
            if(oldMatchingState == MatchingState.AUCTION) {
                executeActivatedSLO(security, changeMatchingStateRq);
            }
        } catch (InvalidRequestException e) {
            publishChangeMatchingStateRqRejectedEvent(changeMatchingStateRq);
        }
    }
    public void executeActivatedSLO(Security security, ChangeMatchingStateRq changeMatchingStateRq){
        Order activatedOrder;
        while ((activatedOrder = (security.getActivateCandidateOrder(matcher.getLastTradePrice()))) != null) {
            if(changeMatchingStateRq != null && changeMatchingStateRq.getTargetState() == MatchingState.AUCTION){
                matcher.auctionExecute(activatedOrder);
                publishOrderActivateEvent(orderIdRqIdMap.get(activatedOrder.getOrderId()), activatedOrder.getOrderId());
                continue;
            }

            MatchResult matchResult = matcher.execute(activatedOrder);

            // TODO: Duplicate
            switch (matchResult.outcome()) {
                case NOT_ENOUGH_CREDIT:
                    publishOrderRejectedEvent(orderIdRqIdMap.get(activatedOrder.getOrderId()),
                            activatedOrder.getOrderId(), List.of(Message.BUYER_HAS_NOT_ENOUGH_CREDIT));
                    return;
                case EXECUTED:
                    publishOrderActivateEvent(orderIdRqIdMap.get(matchResult.remainder().getOrderId()),
                            activatedOrder.getOrderId());
                    break;
            }
            publishIfTradeExists(orderIdRqIdMap.get(activatedOrder.getOrderId()), activatedOrder.getOrderId(), matchResult);
        }
    }
    public void handleDeleteOrder(DeleteOrderRq deleteOrderRq) {
        try {
            validateDeleteOrderRq(deleteOrderRq);

            Security security = securityRepository.findSecurityByIsin(deleteOrderRq.getSecurityIsin());
            security.deleteOrder(deleteOrderRq, matcher);
            publishOrderDeletedEvent(deleteOrderRq);

            if (security.getCurrentMatchingState() == MatchingState.AUCTION) {
                publishOpeningPriceEvent(security.getIsin());
            }
        } catch (InvalidRequestException e) {
            publishOrderRejectedEvent(deleteOrderRq, e.getReasons());
        }
    }


    private void validateEnterOrderAttributes(EnterOrderRq enterOrderRq, List<String> errors) {
        if (enterOrderRq.getOrderId() <= 0)
            errors.add(Message.INVALID_ORDER_ID);
        if (enterOrderRq.getQuantity() <= 0)
            errors.add(Message.ORDER_QUANTITY_NOT_POSITIVE);
        if (enterOrderRq.getPrice() <= 0)
            errors.add(Message.ORDER_PRICE_NOT_POSITIVE);
        if (enterOrderRq.getMinimumExecutionQuantity() < 0)
            errors.add(Message.MEQ_NOT_POSITIVE);
        if (enterOrderRq.getMinimumExecutionQuantity() > enterOrderRq.getQuantity())
            errors.add(Message.MEQ_CANNOT_BE_MORE_THAN_ORDER_QUANTITY);
        if(enterOrderRq.getStopPrice() < 0)
            errors.add(Message.STOP_PRICE_NOT_POSITIVE);
        if(enterOrderRq.getStopPrice() > 0 && enterOrderRq.getMinimumExecutionQuantity() > 0)
            errors.add(Message.ORDER_CANNOT_HAVE_MEQ_AND_BE_STOP_LIMIT);
        if(enterOrderRq.getStopPrice() > 0 && enterOrderRq.getPeakSize() > 0)
            errors.add(Message.ORDER_CANNOT_BE_ICEBERG_AND_STOP_LIMIT);
    }
    private void validateEnterOrderBroker(EnterOrderRq enterOrderRq, List<String> errors) {
        if (brokerRepository.findBrokerById(enterOrderRq.getBrokerId()) == null)
            errors.add(Message.UNKNOWN_BROKER_ID);
    }
    private void validateEnterOrderShareholder(EnterOrderRq enterOrderRq, List<String> errors) {
        if (shareholderRepository.findShareholderById(enterOrderRq.getShareholderId()) == null)
            errors.add(Message.UNKNOWN_SHAREHOLDER_ID);
    }
    private void validateEnterOrderPeakSize(EnterOrderRq enterOrderRq, List<String> errors) {
        if (enterOrderRq.getPeakSize() < 0 || enterOrderRq.getPeakSize() >= enterOrderRq.getQuantity())
            errors.add(Message.INVALID_PEAK_SIZE);
    }
    private void validateEnterOrderSecurity(EnterOrderRq enterOrderRq, List<String> errors) {
        Security security = securityRepository.findSecurityByIsin(enterOrderRq.getSecurityIsin());
        if (security == null)
            errors.add(Message.UNKNOWN_SECURITY_ISIN);
        else {
            if (enterOrderRq.getQuantity() % security.getLotSize() != 0)
                errors.add(Message.QUANTITY_NOT_MULTIPLE_OF_LOT_SIZE);
            if (enterOrderRq.getPrice() % security.getTickSize() != 0)
                errors.add(Message.PRICE_NOT_MULTIPLE_OF_TICK_SIZE);
        }
    }
    private void validateEnterOrderRq(EnterOrderRq enterOrderRq) throws InvalidRequestException {
        List<String> errors = new LinkedList<>();

        validateEnterOrderAttributes(enterOrderRq, errors);
        validateEnterOrderSecurity(enterOrderRq, errors);
        validateEnterOrderBroker(enterOrderRq, errors);
        validateEnterOrderShareholder(enterOrderRq, errors);
        validateEnterOrderPeakSize(enterOrderRq, errors);

        if (!errors.isEmpty()) {
            throw new InvalidRequestException(errors);
        }
    }

    private void validateChangeMatchingStateRqSecurity(ChangeMatchingStateRq changeMatchingStateRq,
                                                       List<String> errors) {
        Security security = securityRepository.findSecurityByIsin(changeMatchingStateRq.getSecurityIsin());
        if (security == null)
            errors.add(Message.UNKNOWN_SECURITY_ISIN);
        if(changeMatchingStateRq.getTargetState() != MatchingState.AUCTION &&
                changeMatchingStateRq.getTargetState() != MatchingState.CONTINUOUS)
            errors.add(Message.INVALID_TARGET_MATCHING_STATE);
    }
    private void validateChangeMatchingStateRq(ChangeMatchingStateRq changeMatchingStateRq)
            throws InvalidRequestException {
        List<String> errors = new LinkedList<>();

        validateChangeMatchingStateRqSecurity(changeMatchingStateRq, errors);

        if (!errors.isEmpty()) {
            throw new InvalidRequestException(errors);
        }
    }

    private void validateDeleteOrderAttributes(DeleteOrderRq deleteOrderRq, List<String> errors) {
        if (deleteOrderRq.getOrderId() <= 0)
            errors.add(Message.INVALID_ORDER_ID);
    }
    private void validateDeleteOrderRqSecurity(DeleteOrderRq deleteOrderRq, List<String> errors) {
        Security security = securityRepository.findSecurityByIsin(deleteOrderRq.getSecurityIsin());
        if (security == null)
            errors.add(Message.UNKNOWN_SECURITY_ISIN);
    }
    private void validateDeleteOrderRq(DeleteOrderRq deleteOrderRq) throws InvalidRequestException {
        List<String> errors = new LinkedList<>();

        validateDeleteOrderAttributes(deleteOrderRq, errors);
        validateDeleteOrderRqSecurity(deleteOrderRq, errors);

        if (!errors.isEmpty()) {
            throw new InvalidRequestException(errors);
        }
    }
}
