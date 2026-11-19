package cloud.cholewa.gate.database;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

//A row of gate_command (V3, HAS-449): an opening of the gate - when, from where (SCREEN, DASHBOARD), by whom from the
//dashboard, and what it came to (OPENED, FAILED, TOO_SOON, UNAVAILABLE). Since V6 (HAS-474) also the pulse of a garage
//gate (PULSED) with the registry id of that gate; gateId is null for the driveway gate
@Table("gate_command")
public record GateCommandEntity(
    @Id Long id,
    @Column("at") LocalDateTime at,
    @Column("source") String source,
    @Column("member") String member,
    @Column("outcome") String outcome,
    @Column("gate_id") Long gateId
) {

    public static final String OPENED = "OPENED";
    public static final String PULSED = "PULSED";
    public static final String FAILED = "FAILED";
    public static final String TOO_SOON = "TOO_SOON";
    public static final String UNAVAILABLE = "UNAVAILABLE";

    //a command of the driveway gate
    public static GateCommandEntity of(final LocalDateTime at, final String source, final String member,
                                       final String outcome) {
        return new GateCommandEntity(null, at, source, member, outcome, null);
    }

    //a command of a garage gate, by its registry id (HAS-474)
    public static GateCommandEntity ofGarage(final long gateId, final LocalDateTime at, final String source,
                                             final String member, final String outcome) {
        return new GateCommandEntity(null, at, source, member, outcome, gateId);
    }
}
