package cloud.cholewa.gate.events;

import cloud.cholewa.gate.api.model.GateEvent;
import cloud.cholewa.gate.database.GarageGateChangeEntity;
import cloud.cholewa.gate.database.GarageGateChangeRepository;
import cloud.cholewa.gate.database.GateCommandEntity;
import cloud.cholewa.gate.database.GateCommandRepository;
import cloud.cholewa.gate.database.IntercomEventEntity;
import cloud.cholewa.gate.database.IntercomEventRepository;
import cloud.cholewa.gate.error.GateException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;

//GET /home/gate/events (HAS-450): what the door station reported and what was sent to the gate in a period of at most
//MAX_PERIOD, as one timeline, the newest first - both tables read newest first and merged by their time. The commands
//(a few a day) are read first into a list, so the read holds one of the two connections of the pool at a time and
//never keeps the listener of the station from writing. No picture: the station gives none (HAS-444). Since HAS-474 also
//the changes of the garage gates (read into a list the same way, before the commands) and the pulses among the commands
@Slf4j
@Service
@RequiredArgsConstructor
public class GateEventService {

    static final Duration MAX_PERIOD = Duration.ofDays(31);

    private static final Comparator<GateEvent> NEWEST_FIRST = Comparator.comparing(GateEvent::at).reversed();

    private final IntercomEventRepository intercomEvents;
    private final GateCommandRepository commands;
    private final GarageGateChangeRepository garageChanges;
    private final Clock clock;

    public Flux<GateEvent> events(final LocalDateTime from, final LocalDateTime to) {
        return Mono.justOrEmpty(problemOf(from, to))
            .flatMap(problem -> Mono.<Void>error(new GateException.InvalidRange(problem)))
            .then(Mono.defer(() -> garageChanges.findInPeriod(from, to).map(this::eventOf).collectList()))
            .zipWhen(changes -> commands.findInPeriod(from, to).map(GateEventService::eventOf).collectList())
            .flatMapMany(lists -> Flux.mergeComparing(NEWEST_FIRST,
                intercomEvents.findInPeriod(from, to).mapNotNull(GateEventService::eventOf),
                Flux.fromIterable(lists.getT2()),
                Flux.fromIterable(lists.getT1())));
    }

    //a change of a garage gate: open, or closed with how long it stood open - two times of the house compared in its zone
    GateEvent eventOf(final GarageGateChangeEntity change) {
        final Long openFor = Boolean.TRUE.equals(change.open()) || change.openSince() == null
            ? null
            : Duration.between(change.openSince().atZone(clock.getZone()), change.at().atZone(clock.getZone())).toSeconds();
        return new GateEvent(GateEvent.GARAGE, change.at(), null, null, null, null, null, null, null, change.gateId(),
            Boolean.TRUE.equals(change.open()) ? "open" : "closed", openFor);
    }

    //from included, to not
    static Optional<String> problemOf(final LocalDateTime from, final LocalDateTime to) {
        if (!from.isBefore(to)) {
            return Optional.of("from must be before to");
        }
        //the wall time of the house plus the days - no duration between two local times (S8700)
        if (to.isAfter(from.plus(MAX_PERIOD))) {
            return Optional.of("The period may be at most " + MAX_PERIOD.toDays() + " days");
        }
        return Optional.empty();
    }

    //a kind this version does not know (a later release wrote it) is left out with a WARN, never served as another
    static GateEvent eventOf(final IntercomEventEntity event) {
        return switch (event.kind()) {
            case IntercomEventEntity.RING -> new GateEvent(GateEvent.RING, event.occurredAt(), event.deviceNumber(),
                null, null, null, null, null, null);
            case IntercomEventEntity.UNLOCK -> new GateEvent(GateEvent.UNLOCK, event.occurredAt(), null, event.door(),
                event.unlockType(), event.number(), null, null, null);
            case IntercomEventEntity.DISMISSED -> new GateEvent(GateEvent.DISMISSED, event.occurredAt(), null, null, null,
                null, null, null, null);
            default -> {
                log.warn("An intercom event {} of a kind not known left out: {}", event.id(), event.kind());
                yield null;
            }
        };
    }

    //the constants of the table in the words of the API: SCREEN - screen, TOO_SOON - too soon
    static GateEvent eventOf(final GateCommandEntity command) {
        return new GateEvent(GateEvent.COMMAND, command.at(), null, null, null, null, wordsOf(command.source()),
            command.member(), wordsOf(command.outcome()), command.gateId(), null, null);
    }

    private static String wordsOf(final String constant) {
        return constant.toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
