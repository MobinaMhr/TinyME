package ir.ramtung.tinyme.messaging.request;

import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@NoArgsConstructor
public class ChangeMatchingStateRq {
    private String securityIsin;
    private MatchingState targetState;

    private ChangeMatchingStateRq(String securityIsin, MatchingState targetState) {
        this.securityIsin = securityIsin;
        this.targetState = targetState;
    }

    public static ChangeMatchingStateRq createNewChangeMatchingStateRq(String securityIsin, MatchingState targetState) {
        return new ChangeMatchingStateRq(securityIsin, targetState);
    }
}
