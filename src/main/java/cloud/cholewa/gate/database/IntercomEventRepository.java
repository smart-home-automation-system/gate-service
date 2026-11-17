package cloud.cholewa.gate.database;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface IntercomEventRepository extends ReactiveCrudRepository<IntercomEventEntity, Long> {
}
