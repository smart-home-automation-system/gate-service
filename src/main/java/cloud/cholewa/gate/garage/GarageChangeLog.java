package cloud.cholewa.gate.garage;

import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.gate.database.GarageGateChangeEntity;
import cloud.cholewa.gate.database.GarageGateChangeRepository;
import cloud.cholewa.gate.gate.GateRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

//The changes of the state of the garage gates in garage_gate_change (HAS-474), for the timeline of GET /events: a sensor
//that changed its value writes a row for every garage gate that names it, at the time its gateway heard it, a closing
//with since when the gate had stood open.
//A change is written only once it held for SETTLE: the sensor of a garage gate bounces where the gate leaves or reaches
//its end (checked on Kate on 2026-11-19 - open, closed, open within 2 s), so the flips that follow each other within
//SETTLE are one change, written with the last of them, and a bounce that ends where it began writes nothing. The flips
//waiting are in memory - one within SETTLE before a restart is not written.
//Never fails nor holds what calls it - the MQTT listener: a flip is only remembered there, the writes are a pass of their
//own, each bounded, a failure a WARN
@Slf4j
@Component
@RequiredArgsConstructor
public class GarageChangeLog {

    static final Duration SETTLE = Duration.ofSeconds(5);
    static final Duration LIMIT = Duration.ofSeconds(4);
    static final Duration WRITE_TIMEOUT = Duration.ofSeconds(3);

    private final GateRegistry gateRegistry;
    private final GarageGateChangeRepository repository;
    private final Clock clock;
    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();

    /**
     * The flips of one sensor within {@link #SETTLE} of each other: {@code before} - its value before the first of them
     * (the opposite of the first flip), {@code open} / {@code heard} - the last flip, {@code openedHeard} - for a chain
     * that began open, when that opening was heard; {@code at} - when the last flip arrived, on the clock of the cluster
     * (the clock of the gateway runs ahead of it).
     */
    record Pending(boolean before, boolean open, Instant heard, Instant openedHeard, Instant at) {
    }

    //heard - when the gateway heard the new value (now when it gave none); openedHeard - for a closing, when the opening
    //before it was heard (null when not known). Only remembered: the change is written once it settled
    Mono<Void> logChange(final long sensorId, final boolean open, final Instant heard, final Instant openedHeard) {
        return Mono.fromRunnable(() -> {
            final Instant now = clock.instant();
            final Instant when = heard == null ? now : heard;
            pending.compute(sensorId, (id, chain) -> chain == null
                ? new Pending(!open, open, when, openedHeard, now)
                : new Pending(chain.before(), open, when, chain.openedHeard(), now));
        });
    }

    @Scheduled(initialDelayString = "PT5S", fixedDelayString = "PT5S")
    public Mono<Void> writeSettled() {
        return ScheduledPass.bounded("garage changes", LIMIT, () -> Mono.defer(this::writeSettledOnce));
    }

    //a chain that took no flip for SETTLE is taken out - unless a flip replaced it meanwhile - and written when it ended
    //on another value than it began with
    Mono<Void> writeSettledOnce() {
        final Instant now = clock.instant();
        final List<Map.Entry<Long, Pending>> settled = pending.entrySet().stream()
            .filter(chain -> !now.isBefore(chain.getValue().at().plus(SETTLE)))
            .toList();
        return Flux.fromIterable(settled)
            .filter(chain -> pending.remove(chain.getKey(), chain.getValue()))
            .filter(chain -> isChange(chain.getKey(), chain.getValue()))
            .concatMap(chain -> write(chain.getKey(), chain.getValue()))
            .then();
    }

    private static boolean isChange(final long sensorId, final Pending chain) {
        if (chain.open() == chain.before()) {
            log.info("Garage sensor {} bounced back to {} - no change", sensorId, chain.open() ? "open" : "closed");
            return false;
        }
        return true;
    }

    private Mono<Void> write(final long sensorId, final Pending chain) {
        final LocalDateTime openSince = chain.open() || chain.openedHeard() == null ? null : house(chain.openedHeard());
        return Flux.fromIterable(gateRegistry.garages())
            .filter(gate -> gate.gate().sensors().contains(sensorId))
            .concatMap(gate -> repository.save(GarageGateChangeEntity.of(gate.gate().id(), house(chain.heard()),
                    chain.open(), openSince))
                .timeout(WRITE_TIMEOUT))
            .then()
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                "The change of garage sensor {} could not be logged: {}", sensorId, failure.toString())));
    }

    private LocalDateTime house(final Instant instant) {
        return LocalDateTime.ofInstant(instant, clock.getZone());
    }
}
