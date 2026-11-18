package cloud.cholewa.gate.database;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface GateCommandRepository extends ReactiveCrudRepository<GateCommandEntity, Long> {

    //the last opening the Shelly took
    Mono<GateCommandEntity> findFirstByOutcomeOrderByAtDesc(String outcome);
}
