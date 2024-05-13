package ir.ramtung.tinyme.messaging.event;

import ir.ramtung.tinyme.messaging.request.MatchingState;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@EqualsAndHashCode(callSuper = false)
@AllArgsConstructor
@NoArgsConstructor
public class SecurityStateChangedEvent extends Event {
    private String securityIsin;    
    private MatchingState state; // TODO : this is state after*** change event request not the previous
}
