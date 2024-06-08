package ir.ramtung.tinyme.domain.service.control;

import ir.ramtung.tinyme.domain.entity.MatchResult;
import ir.ramtung.tinyme.domain.entity.MatchingOutcome;
import ir.ramtung.tinyme.domain.entity.Order;
import ir.ramtung.tinyme.domain.entity.OrderStatus;
import ir.ramtung.tinyme.domain.service.control.MatchingControl;
import org.springframework.stereotype.Component;

@Component
public class MinimumExecutionQuantityControl implements MatchingControl {
    public MatchingOutcome canAcceptMatching(Order order, MatchResult result) {
        if (order.minimumExecutionQuantitySatisfied())
            return MatchingOutcome.OK;
        else return MatchingOutcome.NOT_MET_MEQ_VALUE;
    }
}



