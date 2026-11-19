package cloud.cholewa.gate.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

//An item of GET /home/gate/events (HAS-450): one timeline of the door station and of the gates. kind ring (button),
//dismissed, unlock (door, unlockType, screen - the number of the station), command (source screen / dashboard, member,
//outcome opened / pulsed / failed / too soon / unavailable, and since HAS-474 the gate of a garage gate - left out for the
//driveway gate) or garage (HAS-474: gate, state open / closed, for a closing openForSeconds - how long it stood open); at
//in the time of the house. A field not known, or not of its kind, is left out
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
    String outcome,
    Long gate,
    String state,
    Long openForSeconds
) {

    public static final String RING = "ring";
    public static final String DISMISSED = "dismissed";
    public static final String UNLOCK = "unlock";
    public static final String COMMAND = "command";
    public static final String GARAGE = "garage";

    //an event of the door station or of the driveway gate - no garage gate in it
    public GateEvent(final String kind, final LocalDateTime at, final String button, final Integer door,
                     final String unlockType, final String screen, final String source, final String member,
                     final String outcome) {
        this(kind, at, button, door, unlockType, screen, source, member, outcome, null, null, null);
    }
}
