package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.Security;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.request.ChangeMatchingStateRq;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import ir.ramtung.tinyme.repository.BrokerRepository;
import ir.ramtung.tinyme.repository.SecurityRepository;
import ir.ramtung.tinyme.repository.ShareholderRepository;

import java.util.LinkedList;
import java.util.List;

public class RequestValidator {
    SecurityRepository securityRepository;
    BrokerRepository brokerRepository;
    ShareholderRepository shareholderRepository;
    public RequestValidator(SecurityRepository securityRepository, BrokerRepository brokerRepository,
                        ShareholderRepository shareholderRepository) {
        this.securityRepository = securityRepository;
        this.brokerRepository = brokerRepository;
        this.shareholderRepository = shareholderRepository;
    }
    // TODO -> check if this class is ok?
    //  also check that we have 3 functions named validateRequest with different arguments. is this OK?
    //  also we could pass repsitories to functions.
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
    public void validateRequest(EnterOrderRq enterOrderRq) throws InvalidRequestException {
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
    private void validateChangeMatchingStateRqSecurity(ChangeMatchingStateRq changeMatchingStateRq, List<String> errors) {
        Security security = securityRepository.findSecurityByIsin(changeMatchingStateRq.getSecurityIsin());
        if (security == null)
            errors.add(Message.UNKNOWN_SECURITY_ISIN);
        if(changeMatchingStateRq.getTargetState() != MatchingState.AUCTION &&
                changeMatchingStateRq.getTargetState() != MatchingState.CONTINUOUS)
            errors.add(Message.INVALID_TARGET_MATCHING_STATE);
    }
    public void validateRequest(ChangeMatchingStateRq changeMatchingStateRq) throws InvalidRequestException {
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
    public void validateRequest(DeleteOrderRq deleteOrderRq) throws InvalidRequestException {
        List<String> errors = new LinkedList<>();

        validateDeleteOrderAttributes(deleteOrderRq, errors);
        validateDeleteOrderRqSecurity(deleteOrderRq, errors);

        if (!errors.isEmpty()) {
            throw new InvalidRequestException(errors);
        }
    }
}
