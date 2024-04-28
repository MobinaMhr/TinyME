package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.TradeDTO;
import ir.ramtung.tinyme.messaging.event.*;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.messaging.request.OrderEntryType;
import ir.ramtung.tinyme.repository.BrokerRepository;
import ir.ramtung.tinyme.repository.SecurityRepository;
import ir.ramtung.tinyme.repository.ShareholderRepository;
import org.springframework.stereotype.Service;

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

    public OrderHandler(SecurityRepository securityRepository, BrokerRepository brokerRepository, ShareholderRepository shareholderRepository, EventPublisher eventPublisher, Matcher matcher) {
        this.securityRepository = securityRepository;
        this.brokerRepository = brokerRepository;
        this.shareholderRepository = shareholderRepository;
        this.eventPublisher = eventPublisher;
        this.matcher = matcher;
    }

    public boolean resultPublisher(MatchResult matchResult, EnterOrderRq enterOrderRq) {
        if (matchResult.outcome() == MatchingOutcome.NOT_ENOUGH_CREDIT) {
            eventPublisher.publish(new OrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), List.of(Message.BUYER_HAS_NOT_ENOUGH_CREDIT)));
            return true;
        }
        if (matchResult.outcome() == MatchingOutcome.NOT_ENOUGH_POSITIONS) {
            eventPublisher.publish(new OrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), List.of(Message.SELLER_HAS_NOT_ENOUGH_POSITIONS)));
            return true;
        }
        if (matchResult.outcome() == MatchingOutcome.NOT_MET_MEQ_VALUE) {
            eventPublisher.publish(new OrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), List.of(Message.ORDER_NOT_MET_MEQ_VALUE)));
            return true;
        }
        if (matchResult.outcome() == MatchingOutcome.NOT_MET_LAST_TRADE_PRICE){
            eventPublisher.publish(new OrderAcceptedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId()));
            return true;
        }
        if (enterOrderRq.getRequestType() == OrderEntryType.NEW_ORDER)
            eventPublisher.publish(new OrderAcceptedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId()));
        else
            eventPublisher.publish(new OrderUpdatedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId()));
        if (!matchResult.trades().isEmpty())
            eventPublisher.publish(new OrderExecutedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), matchResult.trades().stream().map(TradeDTO::new).collect(Collectors.toList())));
        return false;
    }
    public void handleEnterOrder(EnterOrderRq enterOrderRq) {
        try {
            validateEnterOrderRq(enterOrderRq);

            Security security = securityRepository.findSecurityByIsin(enterOrderRq.getSecurityIsin());
            Broker broker = brokerRepository.findBrokerById(enterOrderRq.getBrokerId());
            Shareholder shareholder = shareholderRepository.findShareholderById(enterOrderRq.getShareholderId());
            MatchResult matchResult;
            if (enterOrderRq.getRequestType() == OrderEntryType.NEW_ORDER) {
                matchResult = security.newOrder(enterOrderRq, broker, shareholder, matcher);
            }
            else
                matchResult = security.updateOrder(enterOrderRq, matcher);

            if(resultPublisher(matchResult, enterOrderRq))
                return;
            executeActivatedSLO(enterOrderRq, security);
        } catch (InvalidRequestException ex) {
            eventPublisher.publish(new OrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), ex.getReasons()));
        }
    }

    public void executeActivatedSLO(EnterOrderRq enterOrderRq, Security security){
        StopLimitOrder orderUnderActivation = security.getInactiveOrderBook().getActivateCandidateOrders(security.getLastTradePrice());

        while (orderUnderActivation != null){
            MatchResult matchResult = matcher.execute(new Order(orderUnderActivation));

            if (matchResult.outcome() == MatchingOutcome.NOT_ENOUGH_CREDIT) {
                eventPublisher.publish(new OrderRejectedEvent(enterOrderRq.getRequestId(), orderUnderActivation.getOrderId(), List.of(Message.BUYER_HAS_NOT_ENOUGH_CREDIT)));
                return;
            }
            if(matchResult.outcome() == MatchingOutcome.EXECUTED){
                eventPublisher.publish(new OrderActivateEvent(enterOrderRq.getRequestId(), matchResult.remainder().getOrderId()));
            }
            if (!matchResult.trades().isEmpty()) {
                eventPublisher.publish(new OrderExecutedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), matchResult.trades().stream().map(TradeDTO::new).collect(Collectors.toList())));
            }

            orderUnderActivation = security.getInactiveOrderBook().getActivateCandidateOrders(security.getLastTradePrice());
        }
    }

    public void handleDeleteOrder(DeleteOrderRq deleteOrderRq) {
        try {
            validateDeleteOrderRq(deleteOrderRq);
            Security security = securityRepository.findSecurityByIsin(deleteOrderRq.getSecurityIsin());
            security.deleteOrder(deleteOrderRq);
            eventPublisher.publish(new OrderDeletedEvent(deleteOrderRq.getRequestId(), deleteOrderRq.getOrderId()));
        } catch (InvalidRequestException ex) {
            eventPublisher.publish(new OrderRejectedEvent(deleteOrderRq.getRequestId(), deleteOrderRq.getOrderId(), ex.getReasons()));
        }
    }

    private void validateEnterOrderRq(EnterOrderRq enterOrderRq) throws InvalidRequestException {
        List<String> errors = new LinkedList<>();
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
        Security security = securityRepository.findSecurityByIsin(enterOrderRq.getSecurityIsin());
        if (security == null)
            errors.add(Message.UNKNOWN_SECURITY_ISIN);
        else {
            if (enterOrderRq.getQuantity() % security.getLotSize() != 0)
                errors.add(Message.QUANTITY_NOT_MULTIPLE_OF_LOT_SIZE);
            if (enterOrderRq.getPrice() % security.getTickSize() != 0)
                errors.add(Message.PRICE_NOT_MULTIPLE_OF_TICK_SIZE);
        }
        if (brokerRepository.findBrokerById(enterOrderRq.getBrokerId()) == null)
            errors.add(Message.UNKNOWN_BROKER_ID);
        if (shareholderRepository.findShareholderById(enterOrderRq.getShareholderId()) == null)
            errors.add(Message.UNKNOWN_SHAREHOLDER_ID);
        if (enterOrderRq.getPeakSize() < 0 || enterOrderRq.getPeakSize() >= enterOrderRq.getQuantity())
            errors.add(Message.INVALID_PEAK_SIZE);
        if (!errors.isEmpty())
            throw new InvalidRequestException(errors);
    }

    private void validateDeleteOrderRq(DeleteOrderRq deleteOrderRq) throws InvalidRequestException {
        List<String> errors = new LinkedList<>();

        if (deleteOrderRq.getOrderId() <= 0) {
            errors.add(Message.INVALID_ORDER_ID);
        }

        if (securityRepository.findSecurityByIsin(deleteOrderRq.getSecurityIsin()) == null) {
            errors.add(Message.UNKNOWN_SECURITY_ISIN);
        }

        if (!errors.isEmpty()) {
            throw new InvalidRequestException(errors);
        }
    }
}
