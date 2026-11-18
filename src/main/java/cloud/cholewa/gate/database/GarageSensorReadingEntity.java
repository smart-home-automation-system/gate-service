package cloud.cholewa.gate.database;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.time.LocalDateTime;

//the last reading of a sensor on a garage gate (HAS-473, V4): updatedAt the time of its gateway, storedAt house time;
//written by GarageSensorReadingRepository.upsert, never by save()
@Table("garage_sensor_reading")
public record GarageSensorReadingEntity(
    @Id Long sensorId,
    Boolean open,
    Instant updatedAt,
    LocalDateTime storedAt
) {
}
