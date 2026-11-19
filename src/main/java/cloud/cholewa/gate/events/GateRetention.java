package cloud.cholewa.gate.events;

import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.gate.config.GateRetentionProperties;
import cloud.cholewa.gate.database.GarageGateChangeRepository;
import cloud.cholewa.gate.database.GateCommandRepository;
import cloud.cholewa.gate.database.IntercomEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

//Every night at 03:40 of the house the events of the door station, the commands of the gates and the changes of the
//garage gates (HAS-474) older than
//gate.retention (90 days) are deleted (HAS-450). One statement a table, one after the other, each with its own WARN: a
//failed delete is tried again the next night and never keeps the other table. "Now" is read inside the chain - Spring
//subscribes to the same Mono every night; bounded at LIMIT
@Slf4j
@Component
@RequiredArgsConstructor
public class GateRetention {

    static final Duration LIMIT = Duration.ofMinutes(5);

    private final IntercomEventRepository intercomEvents;
    private final GateCommandRepository commands;
    private final GarageGateChangeRepository garageChanges;
    private final GateRetentionProperties properties;
    private final Clock clock;

    @Scheduled(cron = "0 40 3 * * *")
    Mono<Void> purge() {
        return ScheduledPass.bounded("gate retention", LIMIT, () -> Mono.defer(this::purgeOnce));
    }

    private Mono<Void> purgeOnce() {
        final LocalDateTime before = LocalDateTime.now(clock).minus(properties.retention());
        return purge("intercom events", intercomEvents.deleteBefore(before), before)
            .then(Mono.defer(() -> purge("gate commands", commands.deleteBefore(before), before)))
            .then(Mono.defer(() -> purge("garage gate changes", garageChanges.deleteBefore(before), before)));
    }

    private static Mono<Void> purge(final String what, final Mono<Long> delete, final LocalDateTime before) {
        return delete
            .doOnNext(deleted -> log.info("The {} before {} deleted: {}", what, before, deleted))
            .then()
            .onErrorResume(failure -> Mono.fromRunnable(() ->
                log.warn("The {} before {} could not be deleted: {}", what, before, failure.toString())));
    }
}
