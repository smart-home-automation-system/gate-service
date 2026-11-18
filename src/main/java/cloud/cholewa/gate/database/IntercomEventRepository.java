package cloud.cholewa.gate.database;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

public interface IntercomEventRepository extends ReactiveCrudRepository<IntercomEventEntity, Long> {

    //the last event of a kind - the last ring of GET /gate (HAS-449)
    Mono<IntercomEventEntity> findFirstByKindOrderByOccurredAtDesc(String kind);

    //the events of a period (HAS-450): from included, to not; the newest first
    @Query("SELECT * FROM intercom_event WHERE occurred_at >= :from AND occurred_at < :to ORDER BY occurred_at DESC, id DESC")
    Flux<IntercomEventEntity> findInPeriod(LocalDateTime from, LocalDateTime to);

    //the nightly retention (HAS-450): the number of rows deleted
    @Modifying
    @Query("DELETE FROM intercom_event WHERE occurred_at < :before")
    Mono<Long> deleteBefore(LocalDateTime before);
}
