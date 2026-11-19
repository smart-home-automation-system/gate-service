package cloud.cholewa.gate.monitor;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.gate.config.MonitorProperties;
import cloud.cholewa.gate.garage.GarageSensors;
import cloud.cholewa.gate.garage.GarageSensors.GarageReading;
import cloud.cholewa.gate.garage.GarageState;
import cloud.cholewa.gate.gate.GarageGate;
import cloud.cholewa.gate.gate.GateDevice;
import cloud.cholewa.gate.gate.GateRegistry;
import cloud.cholewa.gate.gate.GateRegistry.GarageGateState;
import cloud.cholewa.gate.gate.GateRegistry.GateState;
import cloud.cholewa.gate.intercom.BridgeAvailability;
import cloud.cholewa.gate.monitor.AvailabilityEvaluator.Alert;
import cloud.cholewa.shelly.http.ShellyGen1Client;
import cloud.cholewa.shelly.model.Gen1DeviceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//HAS-453, the DoD: the bridge offline for 6 minutes raises one alert and one info on its return, a garage gate Shelly
//unreachable for 6 minutes the same; an alert that never got through is announced with the return; a relay that does not
//switch itself off is told at once; a garage gate not known for 5 minutes is told; nothing before the gates are read
class AvailabilityMonitorTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    private static final Instant START = Instant.parse("2026-11-19T09:00:00Z");
    private static final GateDevice DRIVEWAY = new GateDevice(147, "Gate", "192.0.2.47", 0);
    private static final GarageGate KATE = new GarageGate(148, "Kate", "192.0.2.79", 0, List.of(69L));

    private final GateRegistry gateRegistry = mock(GateRegistry.class);
    private final ShellyGen1Client shelly = mock(ShellyGen1Client.class);
    private final GarageSensors garageSensors = mock(GarageSensors.class);
    private final NotificationPublisher publisher = mock(NotificationPublisher.class);
    private final BridgeAvailability bridge = new BridgeAvailability();
    private final FakeAlerts alerts = new FakeAlerts();
    private final List<String> told = new ArrayList<>();
    private final AtomicReference<Instant> now = new AtomicReference<>(START);
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return HOUSE;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };
    private final AvailabilityMonitor monitor = new AvailabilityMonitor(gateRegistry, new ShellyReachability(shelly, clock),
        bridge, garageSensors, alerts, publisher, new MonitorProperties(Duration.ofMinutes(5), Duration.ofHours(1)), clock);

    //the table in memory, by the rules of its statements
    private static final class FakeAlerts extends AvailabilityAlertRepository {

        private final Map<String, Alert> rows = new ConcurrentHashMap<>();

        FakeAlerts() {
            super(null);
        }

        @Override
        public Flux<Alert> findAll() {
            return Flux.fromIterable(List.copyOf(rows.values()));
        }

        @Override
        public Mono<Void> due(final Alert alert) {
            return Mono.fromRunnable(() -> rows.putIfAbsent(alert.item(),
                new Alert(alert.item(), alert.name(), alert.since(), false, null)));
        }

        @Override
        public Mono<Void> told(final String item, final LocalDateTime at) {
            return Mono.fromRunnable(() -> rows.computeIfPresent(item,
                (key, row) -> new Alert(row.item(), row.name(), row.since(), true, at)));
        }

        @Override
        public Mono<Void> delete(final String item) {
            return Mono.fromRunnable(() -> rows.remove(item));
        }
    }

    @BeforeEach
    void setUp() {
        when(gateRegistry.current()).thenReturn(Optional.of(new GateState(DRIVEWAY, 1.0, true, null)));
        when(gateRegistry.garages()).thenReturn(List.of(new GarageGateState(KATE, 1.0, true, null)));
        when(shelly.status(anyString())).thenReturn(Mono.just(new Gen1DeviceStatus()));
        when(garageSensors.stateOf(any(GarageGate.class), any(LocalDateTime.class)))
            .thenReturn(new GarageReading(GarageState.CLOSED, null, null));
        when(publisher.publishAlert(anyString())).thenAnswer(call -> remember("alert", call.getArgument(0)));
        when(publisher.publishReminder(anyString())).thenAnswer(call -> remember("reminder", call.getArgument(0)));
        when(publisher.publishInfo(anyString())).thenAnswer(call -> remember("info", call.getArgument(0)));
        bridge.report("Call-state", "online", house(0));
    }

    private Mono<Void> remember(final String kind, final String text) {
        return Mono.fromRunnable(() -> told.add(kind + ": " + text));
    }

    private static LocalDateTime house(final long minutes) {
        return LocalDateTime.ofInstant(START.plusSeconds(minutes * 60), HOUSE);
    }

    private void passAt(final long minutes) {
        now.set(START.plusSeconds(minutes * 60));
        monitor.checkOnce().as(StepVerifier::create).verifyComplete();
    }

    @Test
    void should_tell_the_bridge_offline_for_six_minutes_once_and_its_return_once() {
        passAt(0);
        bridge.report("Call-state", "offline", house(1));
        for (long minute = 1; minute <= 7; minute++) {
            passAt(minute);
        }
        bridge.report("Call-state", "online", house(8));
        passAt(8);
        passAt(9);

        assertThat(told).containsExactly(
            "alert: Gate service - out: the bridge of the door station offline since 2026-11-19 10:01 (5 min).",
            "info: Gate service - back: the bridge of the door station - offline from 2026-11-19 10:01 to 2026-11-19 "
                + "10:08 (7 min).");
    }

    @Test
    void should_tell_a_garage_gate_shelly_unreachable_for_six_minutes_once_and_its_return_once() {
        passAt(0);
        when(shelly.status("192.0.2.79")).thenReturn(Mono.error(new IllegalStateException("timeout")));
        for (long minute = 1; minute <= 7; minute++) {
            passAt(minute);
        }
        when(shelly.status("192.0.2.79")).thenReturn(Mono.just(new Gen1DeviceStatus()));
        passAt(8);
        passAt(9);

        assertThat(told).containsExactly(
            "alert: Gate service - out: the Shelly of the garage gate Kate (148) not answering since 2026-11-19 10:01 "
                + "(5 min).",
            "info: Gate service - back: the Shelly of the garage gate Kate (148) - not answering from 2026-11-19 10:01 "
                + "to 2026-11-19 10:08 (7 min).");
    }

    @Test
    void should_remind_every_hour_while_out() {
        when(shelly.status("192.0.2.47")).thenReturn(Mono.error(new IllegalStateException("timeout")));
        for (long minute = 0; minute <= 66; minute++) {
            passAt(minute);
        }

        assertThat(told).hasSize(2);
        assertThat(told.get(1)).startsWith("reminder: Gate service - still out: the Shelly of the gate Gate (147)");
    }

    //the broker away when the alert was due: tried again with every pass, and the return says it never got through
    @Test
    void should_announce_with_the_return_an_alert_that_never_got_through() {
        when(publisher.publishAlert(anyString())).thenReturn(Mono.error(new IllegalStateException("broker down")));
        bridge.report("Call-state", "offline", house(0));
        passAt(5);
        passAt(6);
        bridge.report("Call-state", "online", house(7));
        passAt(7);

        assertThat(told).containsExactly("info: Gate service - back: the bridge of the door station - offline from "
            + "2026-11-19 10:00 to 2026-11-19 10:07 (7 min), its alert never got through.");
        assertThat(alerts.rows).isEmpty();
    }

    @Test
    void should_tell_a_relay_that_does_not_switch_itself_off_at_once() {
        when(gateRegistry.garages()).thenReturn(List.of(new GarageGateState(KATE, 0.0, true, null)));

        passAt(0);

        assertThat(told).containsExactly("alert: Gate service - out: the relay of the garage gate Kate (148) not "
            + "switching itself off (auto_off 0), the gate not commanded since 2026-11-19 10:00 (0 min).");
    }

    @Test
    void should_tell_a_garage_gate_not_known_for_five_minutes() {
        when(garageSensors.stateOf(any(GarageGate.class), any(LocalDateTime.class)))
            .thenReturn(new GarageReading(GarageState.UNKNOWN, null, null));

        passAt(0);
        passAt(4);
        assertThat(told).isEmpty();
        passAt(5);

        assertThat(told).containsExactly("alert: Gate service - out: the garage gate Kate (148) not known, its opening "
            + "sensors not confirmed by their gateway since 2026-11-19 10:00 (5 min).");
    }

    //before the gates are read every Shelly would look removed and its alert be forgotten
    @Test
    void should_do_nothing_before_the_gates_are_read() {
        when(gateRegistry.current()).thenReturn(Optional.empty());
        when(gateRegistry.garages()).thenReturn(List.of());
        alerts.rows.put("shelly:148", new Alert("shelly:148", "the Shelly of Kate", house(-10), true, house(-5)));

        passAt(0);

        assertThat(alerts.rows).containsKey("shelly:148");
        verify(shelly, never()).status(anyString());
    }

    //the bridge not known after a start is never back
    @Test
    void should_not_announce_the_bridge_back_before_it_said_anything() {
        final AvailabilityMonitor fresh = new AvailabilityMonitor(gateRegistry, new ShellyReachability(shelly, clock),
            new BridgeAvailability(), garageSensors, alerts, publisher,
            new MonitorProperties(Duration.ofMinutes(5), Duration.ofHours(1)), clock);
        alerts.rows.put("bridge", new Alert("bridge", "the bridge of the door station", house(-10), true, house(-5)));

        fresh.checkOnce().as(StepVerifier::create).verifyComplete();

        assertThat(told).isEmpty();
        assertThat(alerts.rows).containsKey("bridge");
    }
}
