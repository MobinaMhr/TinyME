package ir.ramtung.tinyme.messaging.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@EqualsAndHashCode(callSuper = false)
@AllArgsConstructor
@NoArgsConstructor
public class OpeningPriceEvent extends Event {
    private String securityIsin;
    private int openingPrice;
    private int tradableQuantity; // TODO : it is about tradable quantity in Goshayesh time(I didn't get it)
}
