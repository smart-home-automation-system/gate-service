package cloud.cholewa.gate.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

//An item of GET /home/gate/events (HAS-450): one timeline of the door station and of the gate. kind ring (button),
//dismissed, unlock (door, unlockType, screen - the number of the station) or command (source screen / dashboard,
//member, outcome opened / failed / too soon / unavailable); at in the time of the house. A field not known, or not of
//its kind, is left out
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GateEvent(
    String kind,
    LocalDateTime at,
    String button,
    Integer door,
    String unlockType,
    String screen,
    String source,
    String member,
    String outcome
) {

    public static final String RING = "ring";
    public static final String DISMISSED = "dismissed";
    public static final String UNLOCK = "unlock";
    public static final String COMMAND = "command";
}
