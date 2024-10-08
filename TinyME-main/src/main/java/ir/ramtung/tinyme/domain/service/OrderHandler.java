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

import java.util.ArrayList;
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
    RequestValidator requestValidator;
    private  HashMap<Long, Long> orderIdRqIdMap;

    public OrderHandler(SecurityRepository securityRepository, BrokerRepository brokerRepository,
                        ShareholderRepository shareholderRepository, EventPublisher eventPublisher, Matcher matcher) {
        this.securityRepository = securityRepository;
        this.brokerRepository = brokerRepository;
        this.shareholderRepository = shareholderRepository;
        this.eventPublisher = eventPublisher;
        this.matcher = matcher;
        this.orderIdRqIdMap = new HashMap<Long, Long>();
        this.requestValidator = new RequestValidator(securityRepository, brokerRepository, shareholderRepository);
    }

    private void executeActivatedSLO(Security security, MatchingState targetState){
        ArrayList<MatchResult> results = security.activateStopLimitOrder(matcher, targetState);
        for (MatchResult result: results){
            switch (result.outcome()) {
                case NOT_ENOUGH_CREDIT:
                    eventPublisher.publishOrderRejectedEvent(orderIdRqIdMap.get(result.remainder().getOrderId()),
                            result.remainder().getOrderId(), List.of(Message.BUYER_HAS_NOT_ENOUGH_CREDIT));
                    break;
                case ACTIVATED:
                    eventPublisher.publishOrderActivateEvent(orderIdRqIdMap.get(result.remainder().getOrderId()),
                            result.remainder().getOrderId());
                    break;
            }
            if (!result.trades().isEmpty())
                eventPublisher.publishIfTradeExists(orderIdRqIdMap.get(result.remainder().getOrderId()),
                        result.remainder().getOrderId(), result);
        }
    }

    private boolean shouldInactiveOrdersActivate(MatchResult matchResult) {
        if (matchResult != null && matchResult.outcome() == MatchingOutcome.EXECUTED ||
                matchResult.outcome() == MatchingOutcome.EXECUTED_IN_AUCTION)
            return true;
        return false;
    }

    public void publishEnterOrderRq(EnterOrderRq enterOrderRq, MatchResult matchResult,
                                   boolean isTypeStopLimitOrder, Security security) {
        switch (matchResult.outcome()) {
            case EXECUTED:
                if (isTypeStopLimitOrder)
                    eventPublisher.publishOrderActivateEvent(enterOrderRq.getRequestId(),
                            matchResult.remainder().getOrderId());
                break;
            case EXECUTED_IN_AUCTION:
                eventPublisher.publishOpeningPriceEvent(security.getIsin(), matcher.getReopeningPrice(), matcher.maxTradableQuantity);
                break;
            case NOT_ENOUGH_CREDIT:
            case NOT_ENOUGH_POSITIONS:
            case NOT_MET_MEQ_VALUE:
                eventPublisher.publishOrderRejectedEvent(enterOrderRq, matchResult);
                return;
            case NOT_MET_LAST_TRADE_PRICE:
                eventPublisher.publishAcceptedOrderEvent(enterOrderRq);
                orderIdRqIdMap.put(enterOrderRq.getOrderId(), enterOrderRq.getRequestId());
                return;
            case MEQ_ORDER_IS_NOT_ALLOWED_IN_AUCTION:
            case STOP_LIMIT_ORDER_IS_NOT_ALLOWED_IN_AUCTION:
                return;
        }
        if (enterOrderRq.getRequestType() == OrderEntryType.NEW_ORDER) {
            eventPublisher.publishAcceptedOrderEvent(enterOrderRq);
            orderIdRqIdMap.put(enterOrderRq.getOrderId(), enterOrderRq.getRequestId());
        } else {
            eventPublisher.publishOrderUpdatedEvent(enterOrderRq);
            orderIdRqIdMap.put(enterOrderRq.getOrderId(), enterOrderRq.getRequestId());
        }
        if (!matchResult.trades().isEmpty())
            eventPublisher.publishIfTradeExists(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), matchResult);
    }

    public void handleEnterOrder(EnterOrderRq enterOrderRq) {
        try {
            requestValidator.validateRequest(enterOrderRq);
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
            publishEnterOrderRq(enterOrderRq, matchResult, isTypeStopLimitOrder, security);
            if(shouldInactiveOrdersActivate(matchResult)) {
                executeActivatedSLO(security, null);
            }
        } catch (InvalidRequestException e) {
            eventPublisher.publishOrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), e.getReasons());
        }
    }

    private void publishChangeMatchingStateRq(ChangeMatchingStateRq changeMatchingStateRq, MatchResult result, Security security) {
        eventPublisher.publishSecurityStateChangedEvent(changeMatchingStateRq);

        if (result == null || result.trades() == null) return;
        for (Trade trade : result.trades())
            eventPublisher.publishTradeEvents(trade, security.getIsin());
    }
    public void handleChangeMatchingStateRq(ChangeMatchingStateRq changeMatchingStateRq) {
        try {
            requestValidator.validateRequest(changeMatchingStateRq);

            Security security = securityRepository.findSecurityByIsin(changeMatchingStateRq.getSecurityIsin());
            MatchingState oldMatchingState = security.getCurrentMatchingState();
            MatchResult result = security.updateMatchingState(changeMatchingStateRq.getTargetState(), matcher);

            publishChangeMatchingStateRq(changeMatchingStateRq, result, security);

            if(oldMatchingState == MatchingState.AUCTION)
                executeActivatedSLO(security, changeMatchingStateRq.getTargetState());
        } catch (InvalidRequestException e) {
            eventPublisher.publishChangeMatchingStateRqRejectedEvent(changeMatchingStateRq);
        }
    }

    private void publishDeleteOrderRq(DeleteOrderRq deleteOrderRq, Security security) {
        eventPublisher.publishOrderDeletedEvent(deleteOrderRq);
        orderIdRqIdMap.remove(deleteOrderRq.getOrderId());

        if (security.getCurrentMatchingState() == MatchingState.AUCTION) {
            eventPublisher.publishOpeningPriceEvent(security.getIsin(), matcher.getReopeningPrice(), matcher.maxTradableQuantity);
        }
    }

    public void handleDeleteOrder(DeleteOrderRq deleteOrderRq) {
        try {
            requestValidator.validateRequest(deleteOrderRq);

            Security security = securityRepository.findSecurityByIsin(deleteOrderRq.getSecurityIsin());
            security.deleteOrder(deleteOrderRq, matcher);

            publishDeleteOrderRq(deleteOrderRq, security);
        } catch (InvalidRequestException e) {
            eventPublisher.publishOrderRejectedEvent(deleteOrderRq, e.getReasons());
        }
    }
}
