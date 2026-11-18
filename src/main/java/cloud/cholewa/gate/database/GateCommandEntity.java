package cloud.cholewa.gate.database;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

//A row of gate_command (V3, HAS-449): an opening of the gate - when, from where (SCREEN, DASHBOARD), by whom from the
//dashboard, and what it came to (OPENED, FAILED, TOO_SOON, UNAVAILABLE)
@Table("gate_command")
public record GateCommandEntity(
    @Id Long id,
    @Column("at") LocalDateTime at,
    @Column("source") String source,
    @Column("member") String member,
    @Column("outcome") String outcome
) {

    public static final String OPENED = "OPENED";
    public static final String FAILED = "FAILED";
    public static final String TOO_SOON = "TOO_SOON";
    public static final String UNAVAILABLE = "UNAVAILABLE";

    public static GateCommandEntity of(final LocalDateTime at, final String source, final String member,
                                       final String outcome) {
        return new GateCommandEntity(null, at, source, member, outcome);
    }
}
