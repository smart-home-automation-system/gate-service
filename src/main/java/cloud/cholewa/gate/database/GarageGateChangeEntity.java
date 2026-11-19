package cloud.cholewa.gate.database;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

//A change of the state of a garage gate (HAS-474, V6): when its gateway heard it (house time), what it became, and for a
//closing since when the gate had stood open (null when not known)
@Table("garage_gate_change")
public record GarageGateChangeEntity(
    @Id Long id,
    Long gateId,
    LocalDateTime at,
    Boolean open,
    LocalDateTime openSince
) {

    public static GarageGateChangeEntity of(final long gateId, final LocalDateTime at, final boolean open,
                                            final LocalDateTime openSince) {
        return new GarageGateChangeEntity(null, gateId, at, open, open ? null : openSince);
    }
}
