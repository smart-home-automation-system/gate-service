package cloud.cholewa.gate.database;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

//A row of intercom_event (V2, HAS-448): what the door station reported - a ring, a dismissed call or an unlock - when it
//happened in the time of the house and when the service heard it. A ring carries the number of the button of the
//station; an unlock how it was opened (unlock_type), by what (control_source, number) and which door
@Table("intercom_event")
public record IntercomEventEntity(
    @Id Long id,
    @Column("kind") String kind,
    @Column("occurred_at") LocalDateTime occurredAt,
    @Column("received_at") LocalDateTime receivedAt,
    @Column("device_number") String deviceNumber,
    @Column("unlock_type") String unlockType,
    @Column("control_source") String controlSource,
    @Column("number") String number,
    @Column("door") Integer door
) {

    public static final String RING = "RING";
    public static final String DISMISSED = "DISMISSED";
    public static final String UNLOCK = "UNLOCK";

    public static IntercomEventEntity ring(final LocalDateTime at, final String deviceNumber) {
        return new IntercomEventEntity(null, RING, at, at, deviceNumber, null, null, null, null);
    }

    public static IntercomEventEntity dismissed(final LocalDateTime at) {
        return new IntercomEventEntity(null, DISMISSED, at, at, null, null, null, null, null);
    }

    public static IntercomEventEntity unlock(
        final LocalDateTime occurredAt,
        final LocalDateTime receivedAt,
        final String unlockType,
        final String controlSource,
        final String number,
        final int door
    ) {
        return new IntercomEventEntity(null, UNLOCK, occurredAt, receivedAt, null, unlockType, controlSource, number,
            door);
    }
}
