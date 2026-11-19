package cloud.cholewa.gate.monitor;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.gate.config.MonitorProperties;
import cloud.cholewa.gate.garage.GarageSensors;
import cloud.cholewa.gate.garage.GarageState;
import cloud.cholewa.gate.gate.GarageGate;
import cloud.cholewa.gate.gate.GateDevice;
import cloud.cholewa.gate.gate.GateRegistry;
import cloud.cholewa.gate.gate.GateRegistry.GarageGateState;
import cloud.cholewa.gate.gate.GateRegistry.GateState;
import cloud.cholewa.gate.intercom.BridgeAvailability;
import cloud.cholewa.gate.monitor.AvailabilityEvaluator.Alert;
import cloud.cholewa.gate.monitor.AvailabilityEvaluator.Decision;
import cloud.cholewa.gate.monitor.AvailabilityEvaluator.Watched;
import cloud.cholewa.gate.monitor.ShellyReachability.Target;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

//The availability monitor (HAS-453): every minute the Shelly of every gate is read (ShellyReachability), then what the
//service depends on is judged by AvailabilityEvaluator against the alerts of gate_availability_alert - the bridge of the
//door station by its availability topic (offline - its own word or the last will the broker publishes for it; owner
//2026-11-19: no silence of the bridge is judged, it publishes nothing while nothing happens at the gate), the Shelly of
//every gate not answering, a relay that does not switch itself off (auto_off 0 - at once), a garage gate not known
//(its sensors not confirmed by their BTHome gateway for gate.garage.gateway-silent-after) - and what is due goes to the
//technical channel through NotificationPublisher: one return, one alert and one reminder a pass at most, each naming
//everything, no SMS. A row is written when the alert is due and marked told once the broker took it, so an alert that
//never got through is tried again with the next pass and announced with the return all the same. A publish neither fails
//nor stalls the pass for longer than PUBLISH_TIMEOUT
@Slf4j
@Component
@RequiredArgsConstructor
public class AvailabilityMonitor {

    static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(15);
    //below the delay of a minute: the reads of 5 s and three publishes of 15 s at most
    static final Duration LIMIT = Duration.ofSeconds(55);
    static final String BRIDGE = "bridge";
    private static final String SHELLY = "shelly:";
    private static final String AUTO_OFF = "auto-off:";
    private static final String GARAGE = "garage:";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final GateRegistry gateRegistry;
    private final ShellyReachability shellyReachability;
    private final BridgeAvailability bridgeAvailability;
    private final GarageSensors garageSensors;
    private final AvailabilityAlertRepository alertRepository;
    private final NotificationPublisher notificationPublisher;
    private final MonitorProperties properties;
    private final Clock clock;
    //since when a relay is seen not switching itself off, or a garage gate not known - the passes of this monitor
    private final Map<String, LocalDateTime> seenSince = new ConcurrentHashMap<>();

    //two minutes in: the gates are read, the sensors of the garage gates read from their gateway at least once
    @Scheduled(initialDelayString = "PT2M", fixedDelayString = "PT1M")
    Mono<Void> check() {
        return ScheduledPass.bounded("gate availability monitor", LIMIT, this::checkOnce);
    }

    //before the gates are read every Shelly would look removed, and its alert be forgotten
    Mono<Void> checkOnce() {
        return Mono.defer(() -> gateRegistry.current().isEmpty() && gateRegistry.garages().isEmpty()
                ? Mono.empty()
                : shellyReachability.poll(targets()).then(judge()))
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.error("The availability check failed: {}",
                failure.toString())));
    }

    private List<Target> targets() {
        return Stream.concat(
                gateRegistry.current().map(GateState::device).stream()
                    .map(gate -> new Target(gate.id(), nameOf(gate), gate.address())),
                gateRegistry.garages().stream().map(GarageGateState::gate)
                    .map(gate -> new Target(gate.id(), nameOf(gate), gate.address())))
            .toList();
    }

    private Mono<Void> judge() {
        final LocalDateTime now = LocalDateTime.now(clock);
        return alertRepository.findAll()
            .collectMap(Alert::item, Function.identity())
            .flatMap(open -> act(AvailabilityEvaluator.decide(watched(now), open, now, properties.reminderEvery()), now));
    }

    List<Watched> watched(final LocalDateTime now) {
        final List<Watched> watched = new ArrayList<>();
        watched.add(bridge());
        gateRegistry.current().ifPresent(gate -> {
            final String name = nameOf(gate.device());
            watched.add(shelly(gate.device().id(), name));
            watched.add(autoOff(gate.device().id(), name, gate.reachable() && !gate.safe(), gate.reachable(), now));
        });
        gateRegistry.garages().forEach(garage -> {
            final String name = nameOf(garage.gate());
            watched.add(shelly(garage.gate().id(), name));
            watched.add(autoOff(garage.gate().id(), name, garage.reachable() && !garage.safe(), garage.reachable(), now));
            final boolean unknown = garageSensors.stateOf(garage.gate(), now).state() == GarageState.UNKNOWN;
            final String item = GARAGE + garage.gate().id();
            watched.add(new Watched(item, name, seen(item, unknown, now), true, properties.offlineAfter()));
        });
        return watched;
    }

    private static String nameOf(final GateDevice gate) {
        return "the gate " + gate.name() + " (" + gate.id() + ")";
    }

    private static String nameOf(final GarageGate gate) {
        return "the garage gate " + gate.name() + " (" + gate.id() + ")";
    }

    //not known until the bridge says something after a start
    private Watched bridge() {
        return bridgeAvailability.latest()
            .map(report -> new Watched(BRIDGE, "the bridge of the door station", report.online() ? null : report.at(),
                true, properties.offlineAfter()))
            .orElseGet(() -> new Watched(BRIDGE, "the bridge of the door station", null, false,
                properties.offlineAfter()));
    }

    private Watched shelly(final long id, final String name) {
        return shellyReachability.of(id)
            .map(reach -> new Watched(SHELLY + id, "the Shelly of " + name, reach.unreachableSince(), true,
                properties.offlineAfter()))
            .orElseGet(() -> new Watched(SHELLY + id, "the Shelly of " + name, null, false,
                properties.offlineAfter()));
    }

    //a relay that does not switch itself off is told at once: a pulse would be a permanent signal to the drive; not known
    //while its Shelly does not answer
    private Watched autoOff(final long id, final String name, final boolean unsafe, final boolean known,
                            final LocalDateTime now) {
        final String item = AUTO_OFF + id;
        return new Watched(item, "the relay of " + name, known ? seen(item, unsafe, now) : seenSince.get(item), known,
            Duration.ZERO);
    }

    private LocalDateTime seen(final String item, final boolean out, final LocalDateTime now) {
        if (!out) {
            seenSince.remove(item);
            return null;
        }
        return seenSince.computeIfAbsent(item, key -> now);
    }

    //the things no longer watched first, then the returns, the alerts and the reminders, each after the one before
    private Mono<Void> act(final Decision decision, final LocalDateTime now) {
        final LocalDateTime at = now.truncatedTo(ChronoUnit.MICROS);
        return Flux.fromIterable(decision.forgotten())
            .concatMap(alert -> alertRepository.delete(alert.item())
                .doOnSuccess(done -> log.info("Availability alert of {} closed: it is no longer watched", alert.item())))
            .then(publish(decision.returns(), notificationPublisher::publishInfo, returnText(decision.returns(), now))
                .flatMap(sent -> Flux.fromIterable(decision.returns())
                    .concatMap(alert -> alertRepository.delete(alert.item())).then()))
            .then(Flux.fromIterable(decision.alerts())
                .concatMap(alert -> alertRepository.due(truncated(alert)))
                .then(publish(decision.alerts(), notificationPublisher::publishAlert,
                    alertText("Gate service - out: ", decision.alerts(), now)))
                .flatMap(sent -> Flux.fromIterable(decision.alerts())
                    .concatMap(alert -> alertRepository.told(alert.item(), at)).then()))
            .then(publish(decision.reminders(), notificationPublisher::publishReminder,
                alertText("Gate service - still out: ", decision.reminders(), now))
                .flatMap(sent -> Flux.fromIterable(decision.reminders())
                    .concatMap(alert -> alertRepository.told(alert.item(), at)).then()));
    }

    //true once the broker took it; empty when there was nothing to say or it failed (logged, tried again)
    private Mono<Boolean> publish(final List<Alert> alerts, final Function<String, Mono<Void>> publisher,
                                  final String text) {
        return Mono.just(alerts)
            .filter(due -> !due.isEmpty())
            .flatMap(due -> publisher.apply(text)
                .timeout(PUBLISH_TIMEOUT)
                .thenReturn(true)
                .doOnNext(sent -> log.info("Availability notification published: {}", text))
                .onErrorResume(failure -> Mono.fromRunnable(() -> log.error(
                    "Availability notification not published, it is tried again with the next pass: {}",
                    failure.toString()))));
    }

    String alertText(final String lead, final List<Alert> alerts, final LocalDateTime now) {
        return lead + list(alerts, alert -> alert.name() + " " + problem(alert.item()) + " since "
            + TIME.format(alert.since()) + " (" + span(alert.since(), now) + ")") + ".";
    }

    String returnText(final List<Alert> alerts, final LocalDateTime now) {
        return "Gate service - back: " + list(alerts, alert -> alert.name() + " - " + problem(alert.item()) + " from "
            + TIME.format(alert.since()) + " to " + TIME.format(now) + " (" + span(alert.since(), now) + ")"
            + (alert.told() ? "" : ", its alert never got through")) + ".";
    }

    private static String problem(final String item) {
        if (BRIDGE.equals(item)) {
            return "offline";
        }
        if (item.startsWith(SHELLY)) {
            return "not answering";
        }
        if (item.startsWith(AUTO_OFF)) {
            return "not switching itself off (auto_off 0), the gate not commanded";
        }
        return "not known, its opening sensors not confirmed by their gateway";
    }

    private static String list(final List<Alert> alerts, final Function<Alert, String> line) {
        return alerts.stream().map(line).collect(Collectors.joining("; "));
    }

    //the minutes of the wall clock, as the times the message names
    private static String span(final LocalDateTime since, final LocalDateTime until) {
        final long total = (until.toEpochSecond(ZoneOffset.UTC) - since.toEpochSecond(ZoneOffset.UTC)) / 60;
        return total < 60 ? total + " min" : total / 60 + " h " + total % 60 + " min";
    }

    private static Alert truncated(final Alert alert) {
        return new Alert(alert.item(), alert.name(), alert.since().truncatedTo(ChronoUnit.MICROS), alert.told(),
            alert.lastAlertAt());
    }
}
