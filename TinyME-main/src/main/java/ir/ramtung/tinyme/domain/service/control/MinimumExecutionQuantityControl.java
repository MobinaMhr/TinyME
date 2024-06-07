package ir.ramtung.tinyme.domain.service.control;

import ir.ramtung.tinyme.domain.entity.MatchResult;
import ir.ramtung.tinyme.domain.entity.MatchingOutcome;
import ir.ramtung.tinyme.domain.entity.Order;
import ir.ramtung.tinyme.domain.entity.OrderStatus;
import ir.ramtung.tinyme.domain.service.control.MatchingControl;
import org.springframework.stereotype.Component;

@Component
public class MinimumExecutionQuantityControl implements MatchingControl {
    public MatchingOutcome doesMetMEQValue(Order order, MatchResult result, int prevQuantity) {
        if (order.getStatus() == OrderStatus.NEW &&
                !result.remainder().minimumExecutionQuantitySatisfied(prevQuantity))
            return MatchingOutcome.NOT_MET_MEQ_VALUE;
        else return MatchingOutcome.OK;
    }
}
