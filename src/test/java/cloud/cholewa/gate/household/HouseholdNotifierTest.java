package cloud.cholewa.gate.household;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.gate.config.DoorbellProperties;
import cloud.cholewa.gate.gate.GateDevice;
import cloud.cholewa.gate.gate.GateRegistry;
import cloud.cholewa.gate.gate.IntercomDevice;
import cloud.cholewa.home.model.GateOpenSource;
import cloud.cholewa.home.model.HouseholdEvent;
import cloud.cholewa.home.model.HouseholdNotification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//HAS-452: what the household is told of the door station and the gate, as the registry of the house holds them
class HouseholdNotifierTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    //08:22:56 of the house - the first ring of 2026-11-18
    private static final Instant RING = Instant.parse("2026-11-18T07:22:56Z");
    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 18, 8, 22, 56);
    private static final IntercomDevice STATION = new IntercomDevice(146L, "Intercom");
    private static final GateDevice GATE = new GateDevice(147L, "Gate", "192.0.2.85", 0);

    private final NotificationPublisher publisher = mock(NotificationPublisher.class);
    private final GateRegistry gateRegistry = mock(GateRegistry.class);
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final AtomicReference<Instant> now = new AtomicReference<>(RING);
    private final HouseholdNotifier notifier = new HouseholdNotifier(publisher, gateRegistry,
        new DoorbellProperties(Duration.ofSeconds(60)), jsonMapper, new Clock() {
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
        });

    @BeforeEach
    void setUp() {
        when(gateRegistry.currentIntercom()).thenReturn(Optional.of(STATION));
        when(publisher.publishHousehold(any())).thenReturn(Mono.empty());
    }

    private List<HouseholdNotification> published(final int times) {
        final ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(publisher, times(times)).publishHousehold(json.capture());
        return json.getAllValues().stream()
            .map(body -> jsonMapper.readValue(body, HouseholdNotification.class))
            .toList();
    }

    @Test
    void should_tell_a_ring_with_the_door_station_and_the_gate_page() {
        notifier.doorbell(AT).as(StepVerifier::create).verifyComplete();

        assertThat(published(1)).containsExactly(HouseholdNotification.builder()
            .event(HouseholdEvent.DOORBELL_RINGING).subject("Intercom").deviceId(146L).occurredAt(AT).reminder(0)
            .acknowledgePath("/gate").build());
    }

    //a visitor pressing the button three times in a minute is one call - one message; a ring after it is told again
    @Test
    void should_tell_a_ring_once_per_call() {
        notifier.doorbell(AT).as(StepVerifier::create).verifyComplete();
        now.set(RING.plusSeconds(20));
        notifier.doorbell(AT.plusSeconds(20)).as(StepVerifier::create).verifyComplete();
        now.set(RING.plusSeconds(59));
        notifier.doorbell(AT.plusSeconds(59)).as(StepVerifier::create).verifyComplete();
        now.set(RING.plusSeconds(60));
        notifier.doorbell(AT.plusSeconds(60)).as(StepVerifier::create).verifyComplete();

        assertThat(published(2)).extracting(HouseholdNotification::getOccurredAt)
            .containsExactly(AT, AT.plusSeconds(60));
    }

    //a ring the broker refused gives the window back: the next press of the same call is told
    @Test
    void should_tell_the_next_press_when_a_ring_could_not_be_told() {
        when(publisher.publishHousehold(any())).thenReturn(Mono.error(new IllegalStateException("nack")), Mono.empty());

        notifier.doorbell(AT).as(StepVerifier::create).verifyComplete();
        now.set(RING.plusSeconds(20));
        notifier.doorbell(AT.plusSeconds(20)).as(StepVerifier::create).verifyComplete();
        now.set(RING.plusSeconds(40));
        notifier.doorbell(AT.plusSeconds(40)).as(StepVerifier::create).verifyComplete();

        verify(publisher, times(2)).publishHousehold(any());
    }

    //before the first read of the registry there is no subject to tell the ring by
    @Test
    void should_tell_nothing_before_the_registry_knows_the_door_station() {
        when(gateRegistry.currentIntercom()).thenReturn(Optional.empty());

        notifier.doorbell(AT).as(StepVerifier::create).verifyComplete();

        verify(publisher, never()).publishHousehold(any());
    }

    @Test
    void should_tell_an_opening_with_where_from_and_who() {
        notifier.gateOpened(GATE, GateOpenSource.DASHBOARD, "Anna", AT).as(StepVerifier::create).verifyComplete();
        notifier.gateOpened(GATE, GateOpenSource.SCREEN, null, AT).as(StepVerifier::create).verifyComplete();

        assertThat(published(2)).containsExactly(
            HouseholdNotification.builder().event(HouseholdEvent.GATE_OPENED).subject("Gate").deviceId(147L)
                .occurredAt(AT).reminder(0).source(GateOpenSource.DASHBOARD).member("Anna").build(),
            HouseholdNotification.builder().event(HouseholdEvent.GATE_OPENED).subject("Gate").deviceId(147L)
                .occurredAt(AT).reminder(0).source(GateOpenSource.SCREEN).build());
    }

    //a notification never fails what it reports on
    @Test
    void should_complete_when_the_broker_refuses() {
        when(publisher.publishHousehold(any())).thenReturn(Mono.error(new IllegalStateException("nack")));

        notifier.doorbell(AT).as(StepVerifier::create).verifyComplete();
        notifier.gateOpened(GATE, GateOpenSource.SCREEN, null, AT).as(StepVerifier::create).verifyComplete();
    }

    //nor holds it longer than the bound
    @Test
    void should_give_up_a_publish_that_never_completes() {
        when(publisher.publishHousehold(any())).thenReturn(Mono.never());

        StepVerifier.withVirtualTime(() -> notifier.gateOpened(GATE, GateOpenSource.SCREEN, null, AT))
            .thenAwait(HouseholdNotifier.PUBLISH_TIMEOUT.plusSeconds(1))
            .verifyComplete();
    }
}
