package cloud.cholewa.gate.database;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface IntercomEventRepository extends ReactiveCrudRepository<IntercomEventEntity, Long> {

    //the last event of a kind - the last ring of GET /gate (HAS-449)
    Mono<IntercomEventEntity> findFirstByKindOrderByOccurredAtDesc(String kind);
}
