package cloud.cholewa.gate.api.model;

import cloud.cholewa.gate.garage.GarageState;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * A garage gate (HAS-473): its registry id and name, what its sensors say - {@code state} {@code open},
 * {@code closed} or {@code unknown}, {@code since} when its gateway heard the reading that decides it (left out when not
 * known), {@code confirmedAt} when a gateway last stood for its sensors - and whether its Shelly answered and its relay
 * switches itself off (only then is it commanded, HAS-474). A field not known is left out, never defaulted; a missing
 * state is never read as closed. The fields are wire contract for the dashboard (HAS-454).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GarageGateReply(
    long id,
    String name,
    GarageState state,
    LocalDateTime since,
    LocalDateTime confirmedAt,
    Boolean reachable,
    Boolean safe
) {
}
