package cloud.cholewa.gate.database;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;

public interface GarageSensorReadingRepository extends ReactiveCrudRepository<GarageSensorReadingEntity, Long> {

    //one statement, PostgreSQL's own: a row per sensor, replaced unless the row holds a reading its gateway heard later -
    //the MQTT listener and the read of the gateways write side by side, and an older one must not win
    @Modifying
    @Query("""
        INSERT INTO garage_sensor_reading (sensor_id, open, updated_at, stored_at)
        VALUES (:sensorId, :open, :updatedAt, :storedAt)
        ON CONFLICT (sensor_id) DO UPDATE
        SET open = EXCLUDED.open,
            updated_at = EXCLUDED.updated_at,
            stored_at = EXCLUDED.stored_at
        WHERE garage_sensor_reading.updated_at IS NULL
           OR EXCLUDED.updated_at IS NULL
           OR EXCLUDED.updated_at >= garage_sensor_reading.updated_at
        """)
    Mono<Integer> upsert(Long sensorId, Boolean open, Instant updatedAt, LocalDateTime storedAt);

    //the rows of the sensors the registry no longer lists; never called with an empty collection
    @Modifying
    @Query("DELETE FROM garage_sensor_reading WHERE sensor_id NOT IN (:sensorIds)")
    Mono<Integer> deleteOthers(Collection<Long> sensorIds);
}
