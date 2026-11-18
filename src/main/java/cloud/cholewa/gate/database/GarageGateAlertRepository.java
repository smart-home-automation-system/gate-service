package cloud.cholewa.gate.database;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface GarageGateAlertRepository extends ReactiveCrudRepository<GarageGateAlertEntity, Long> {

    //the episodes still open, of every garage gate - a handful of rows, read once a pass
    Flux<GarageGateAlertEntity> findByClosedAtIsNull();
}
