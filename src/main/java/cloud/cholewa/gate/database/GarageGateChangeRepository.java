package cloud.cholewa.gate.database;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

public interface GarageGateChangeRepository extends ReactiveCrudRepository<GarageGateChangeEntity, Long> {

    //the changes of a period: from included, to not; the newest first
    @Query("SELECT * FROM garage_gate_change WHERE at >= :from AND at < :to ORDER BY at DESC, id DESC")
    Flux<GarageGateChangeEntity> findInPeriod(LocalDateTime from, LocalDateTime to);

    //the nightly retention: the number of rows deleted
    @Modifying
    @Query("DELETE FROM garage_gate_change WHERE at < :before")
    Mono<Long> deleteBefore(LocalDateTime before);
}
