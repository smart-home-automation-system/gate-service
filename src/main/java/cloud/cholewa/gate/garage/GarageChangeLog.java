package cloud.cholewa.gate.garage;

import cloud.cholewa.gate.database.GarageGateChangeEntity;
import cloud.cholewa.gate.database.GarageGateChangeRepository;
import cloud.cholewa.gate.gate.GateRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;

//The changes of the state of the garage gates in garage_gate_change (HAS-474), for the timeline of GET /events: a sensor
//that changed its value writes a row for every garage gate that names it, at the time its gateway heard it, a closing
//with since when the gate had stood open. Never fails nor holds what calls it - the MQTT listener: a write is bounded
//and a failure is a WARN
@Slf4j
@Component
@RequiredArgsConstructor
public class GarageChangeLog {

    static final Duration WRITE_TIMEOUT = Duration.ofSeconds(30);

    private final GateRegistry gateRegistry;
    private final GarageGateChangeRepository repository;
    private final Clock clock;

    //heard - when the gateway heard the new value (now when it gave none); openedHeard - for a closing, when the opening
    //before it was heard (null when not known)
    Mono<Void> record(final long sensorId, final boolean open, final Instant heard, final Instant openedHeard) {
        return Flux.defer(() -> Flux.fromIterable(gateRegistry.garages()))
            .filter(gate -> gate.gate().sensors().contains(sensorId))
            .concatMap(gate -> repository.save(GarageGateChangeEntity.of(gate.gate().id(),
                    house(heard == null ? clock.instant() : heard), open, openedHeard == null ? null : house(openedHeard)))
                .timeout(WRITE_TIMEOUT))
            .then()
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                "The change of garage sensor {} could not be logged: {}", sensorId, failure.toString())));
    }

    private LocalDateTime house(final Instant instant) {
        return LocalDateTime.ofInstant(instant, clock.getZone());
    }
}
