package cloud.cholewa.gate.database;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

//An alarm episode of a garage gate (HAS-475, V5): kind COLD, EMPTY_HOUSE or LONG; the times are house time
@Table("garage_gate_alert")
public record GarageGateAlertEntity(
    @Id Long id,
    Long gateId,
    String kind,
    LocalDateTime openSince,
    LocalDateTime raisedAt,
    LocalDateTime lastMessageAt,
    Integer reminders,
    LocalDateTime closedAt
) {

    public static GarageGateAlertEntity raised(final long gateId, final String kind, final LocalDateTime openSince,
                                               final LocalDateTime at) {
        return new GarageGateAlertEntity(null, gateId, kind, openSince, at, at, 0, null);
    }

    public GarageGateAlertEntity reminded(final LocalDateTime at) {
        return new GarageGateAlertEntity(id, gateId, kind, openSince, raisedAt, at, reminders + 1, null);
    }

    public GarageGateAlertEntity closed(final LocalDateTime at) {
        return new GarageGateAlertEntity(id, gateId, kind, openSince, raisedAt, lastMessageAt, reminders, at);
    }
}
