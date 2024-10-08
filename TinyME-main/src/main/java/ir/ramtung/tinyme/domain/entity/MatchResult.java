package ir.ramtung.tinyme.domain.entity;

import ir.ramtung.tinyme.messaging.event.TradeEvent;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class MatchResult {
    private final LinkedList<Trade> trades;
    private final MatchingOutcome outcome;
    private final Order remainder;
    private MatchResult(MatchingOutcome outcome, Order remainder, LinkedList<Trade> trades) {
        this.outcome = outcome;
        this.remainder = remainder;
        this.trades = trades;
    }

    public MatchResult(MatchingOutcome outcome, Order remainder) {
        this(outcome, remainder, new LinkedList<>());
    }

    public static MatchResult executed(Order remainder, List<Trade> trades) {
        return new MatchResult(MatchingOutcome.EXECUTED,
                remainder, new LinkedList<>(trades));
    }
    public static MatchResult executed(List<Trade> trades) {
        return new MatchResult(MatchingOutcome.EXECUTED_IN_AUCTION,
                null, new LinkedList<>(trades));
    }
    public static MatchResult activated(Order order) {
        return new MatchResult(MatchingOutcome.ACTIVATED,
                order, new LinkedList<>());
    }
    public static MatchResult notEnoughCredit() {
        return new MatchResult(MatchingOutcome.NOT_ENOUGH_CREDIT,
                null, new LinkedList<>());
    }
    public static MatchResult notEnoughPositions() {
        return new MatchResult(MatchingOutcome.NOT_ENOUGH_POSITIONS,
                null, new LinkedList<>());
    }
    public static MatchResult notMetMEQValue() {
        return new MatchResult(MatchingOutcome.NOT_MET_MEQ_VALUE,
                null, new LinkedList<>());
    }
    public static MatchResult notMetLastTradePrice() {
        return new MatchResult(MatchingOutcome.NOT_MET_LAST_TRADE_PRICE,
                null, new LinkedList<>());
    }
    public static MatchResult stopLimitOrderIsNotAllowedInAuction() {
        return new MatchResult(MatchingOutcome.STOP_LIMIT_ORDER_IS_NOT_ALLOWED_IN_AUCTION,
                null, new LinkedList<>());
    }
    public static MatchResult meqOrderIsNotAllowedInAuction() {
        return new MatchResult(MatchingOutcome.MEQ_ORDER_IS_NOT_ALLOWED_IN_AUCTION,
                null, new LinkedList<>());
    }
    public static MatchResult executed() {
        return new MatchResult(MatchingOutcome.EXECUTED_IN_AUCTION,
                null, new LinkedList<>());
    }

    public MatchingOutcome outcome() {
        return outcome;
    }
    public Order remainder() {
        return remainder;
    }

    public LinkedList<Trade> trades() {
        return trades;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (MatchResult) obj;
        return Objects.equals(this.remainder, that.remainder) &&
                Objects.equals(this.trades, that.trades);
    }

    @Override
    public int hashCode() {
        return Objects.hash(remainder, trades);
    }

    @Override
    public String toString() {
        return "MatchResult[" +
                "remainder=" + remainder + ", " +
                "trades=" + trades + ']';
    }
}
