package cloud.cholewa.gate.monitor;

import cloud.cholewa.gate.monitor.AvailabilityEvaluator.Alert;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

//The alerts of the availability monitor (HAS-453, V7), a statement each
@Repository
@RequiredArgsConstructor
public class AvailabilityAlertRepository {

    private static final String ITEM = "item";

    private final DatabaseClient databaseClient;

    public Flux<Alert> findAll() {
        return databaseClient.sql("SELECT item, name, since, told, last_alert_at FROM gate_availability_alert")
            .map(row -> new Alert(
                row.get(ITEM, String.class),
                row.get("name", String.class),
                row.get("since", LocalDateTime.class),
                Boolean.TRUE.equals(row.get("told", Boolean.class)),
                row.get("last_alert_at", LocalDateTime.class)))
            .all();
    }

    //written when the alert is due, before it is told: an alert that never gets through is still announced with the return
    public Mono<Void> due(final Alert alert) {
        return databaseClient.sql("""
                INSERT INTO gate_availability_alert (item, name, since, told)
                VALUES (:item, :name, :since, FALSE)
                ON CONFLICT (item) DO NOTHING
                """)
            .bind(ITEM, alert.item())
            .bind("name", alert.name())
            .bind("since", alert.since())
            .fetch().rowsUpdated().then();
    }

    //the alert or a reminder got through
    public Mono<Void> told(final String item, final LocalDateTime at) {
        return databaseClient.sql(
                "UPDATE gate_availability_alert SET told = TRUE, last_alert_at = :at WHERE item = :item")
            .bind("at", at)
            .bind(ITEM, item)
            .fetch().rowsUpdated().then();
    }

    public Mono<Void> delete(final String item) {
        return databaseClient.sql("DELETE FROM gate_availability_alert WHERE item = :item")
            .bind(ITEM, item)
            .fetch().rowsUpdated().then();
    }
}
