package cloud.cholewa.gate.database;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

public interface GateCommandRepository extends ReactiveCrudRepository<GateCommandEntity, Long> {

    //the last opening the Shelly took
    Mono<GateCommandEntity> findFirstByOutcomeOrderByAtDesc(String outcome);

    //the commands of a period (HAS-450): from included, to not; the newest first
    @Query("SELECT * FROM gate_command WHERE at >= :from AND at < :to ORDER BY at DESC, id DESC")
    Flux<GateCommandEntity> findInPeriod(LocalDateTime from, LocalDateTime to);

    //the nightly retention (HAS-450): the number of rows deleted
    @Modifying
    @Query("DELETE FROM gate_command WHERE at < :before")
    Mono<Long> deleteBefore(LocalDateTime before);
}
