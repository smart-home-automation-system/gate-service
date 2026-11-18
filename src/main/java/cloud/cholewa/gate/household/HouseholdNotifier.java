package cloud.cholewa.gate.household;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.gate.config.DoorbellProperties;
import cloud.cholewa.gate.gate.GarageGate;
import cloud.cholewa.gate.gate.GateDevice;
import cloud.cholewa.gate.gate.GateRegistry;
import cloud.cholewa.gate.gate.IntercomDevice;
import cloud.cholewa.home.model.GateOpenSource;
import cloud.cholewa.home.model.HouseholdEvent;
import cloud.cholewa.home.model.HouseholdNotification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

//Tells the household (HAS-452) - the household category of the notification exchange, worded in Polish by
//notification-service >= 0.12.0 on its intercom channel: a ring at the gate (pushed to every member) and an opening of the
//gate. Neither ever fails nor stalls what it reports on: an error is a WARN, a publish is cut off after PUBLISH_TIMEOUT.
//A ring is told once per call - no second one within gate.doorbell.ignore-repeat-for of the last one told. The door
//station of the registry is its subject, and before the first read of the registry nothing is told (a WARN). A ring
//that could not be told gives the window back, so the next press of the same call is told. It opens the gate page of
//the dashboard (HAS-454). An opening is told after a pulse the Shelly took, with where from and, from the dashboard, who.
@Slf4j
@Component
@RequiredArgsConstructor
public class HouseholdNotifier {

    static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(30);
    static final String GATE_PAGE = "/gate";

    private final NotificationPublisher notificationPublisher;
    private final GateRegistry gateRegistry;
    private final DoorbellProperties doorbellProperties;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    //the instant of the last ring told - an instant, so the window holds on the night the clocks go back
    private final AtomicReference<Instant> lastRingTold = new AtomicReference<>();

    public Mono<Void> doorbell(final LocalDateTime at) {
        return Mono.defer(() -> gateRegistry.currentIntercom()
                .map(station -> {
                    final Instant now = clock.instant();
                    return Mono.just(station)
                        .filter(known -> claimRing(now))
                        .switchIfEmpty(Mono.fromRunnable(() -> log.info(
                            "A ring within {} of the last one told - the same call, not told again",
                            doorbellProperties.ignoreRepeatFor())))
                        .flatMap(known -> publish(ring(known, at), "doorbell ringing"))
                        .filter(told -> !told)
                        .doOnNext(notTold -> lastRingTold.compareAndSet(now, null))
                        .then();
                })
                .orElseGet(() -> Mono.fromRunnable(() ->
                    log.warn("A ring at {} not told to the household: the registry knows no door station yet", at))));
    }

    public Mono<Void> gateOpened(final GateDevice gate, final GateOpenSource source, final String member,
                                 final LocalDateTime at) {
        return Mono.defer(() -> publish(HouseholdNotification.builder()
            .event(HouseholdEvent.GATE_OPENED)
            .subject(gate.name())
            .deviceId(gate.id())
            .occurredAt(at)
            .reminder(0)
            .source(source)
            .member(member)
            .build(), "gate opened").then());
    }

    //A garage gate left open (HAS-475): an alarm, the warning or its repeat, or the gate closed after it - true once the
    //broker confirmed it, false when it could not be told (a WARN), never an error: the alarms mark an episode told only
    //by it, and try again the next pass
    public Mono<Boolean> garageGate(final HouseholdEvent event, final GarageGate gate, final LocalDateTime openSince,
                                    final int reminder, final Double temperature, final LocalDateTime at) {
        return Mono.defer(() -> publish(HouseholdNotification.builder()
            .event(event)
            .subject(gate.name())
            .deviceId(gate.id())
            .occurredAt(at)
            .reminder(reminder)
            .openSince(openSince)
            .temperature(temperature)
            .acknowledgePath(GATE_PAGE)
            .build(), event.getValue()));
    }

    private static HouseholdNotification ring(final IntercomDevice station, final LocalDateTime at) {
        return HouseholdNotification.builder()
            .event(HouseholdEvent.DOORBELL_RINGING)
            .subject(station.name())
            .deviceId(station.id())
            .occurredAt(at)
            .reminder(0)
            .acknowledgePath(GATE_PAGE)
            .build();
    }

    //the window of a call: refused while the last ring told is younger than ignore-repeat-for
    private boolean claimRing(final Instant now) {
        final Duration window = doorbellProperties.ignoreRepeatFor();
        while (true) {
            final Instant last = lastRingTold.get();
            if (last != null && now.isBefore(last.plus(window))) {
                return false;
            }
            if (lastRingTold.compareAndSet(last, now)) {
                return true;
            }
        }
    }

    //true once the broker confirmed it, false when it could not be told (a WARN) - never an error
    private Mono<Boolean> publish(final HouseholdNotification notification, final String what) {
        return Mono.fromCallable(() -> jsonMapper.writeValueAsString(notification))
            .flatMap(notificationPublisher::publishHousehold)
            .timeout(PUBLISH_TIMEOUT)
            .then(Mono.fromCallable(() -> {
                log.info("The household told: {} of {}", what, notification.getSubject());
                return true;
            }))
            .onErrorResume(failure -> {
                log.warn("The household could not be told: {} of {} - {}", what, notification.getSubject(),
                    failure.toString());
                return Mono.just(false);
            });
    }
}
