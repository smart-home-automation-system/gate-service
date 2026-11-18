package cloud.cholewa.gate.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

//GET /home/gate (HAS-449): the gate, the bridge of the door station, the last ring and the last opening. A part or a field
//not known is left out, never defaulted - a gate the registry was never read for, a bridge that said nothing since the
//start, no ring or no opening stored yet
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GateReply(Gate gate, Bridge bridge, Ring lastRing, Opening lastOpening) {

    //the Shelly of the gate: its registry id, whether its last read answered, whether its relay switches itself off (only
    //then is it commanded), and when it was read
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Gate(Long id, Boolean reachable, Boolean safe, Double autoOffSeconds, LocalDateTime checkedAt) {
    }

    //the last availability the bridge published and when it was heard
    public record Bridge(boolean online, LocalDateTime at) {
    }

    //the button of the station it came from
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Ring(LocalDateTime at, String button) {
    }

    //screen or dashboard, and who opened it from the dashboard
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Opening(LocalDateTime at, String source, String member) {
    }
}
