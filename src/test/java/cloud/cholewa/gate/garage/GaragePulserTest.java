package cloud.cholewa.gate.garage;

import cloud.cholewa.gate.database.GateCommandEntity;
import cloud.cholewa.gate.database.GateCommandRepository;
import cloud.cholewa.gate.error.GateException;
import cloud.cholewa.gate.gate.GarageGate;
import cloud.cholewa.gate.gate.GateRegistry;
import cloud.cholewa.shelly.http.ShellyGen1Client;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//HAS-474 (owner 2026-11-18): one pulse at once, every time - a second press stops the gate half-way; whether it reacted
//is told by its sensor
class GaragePulserTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    private static final Instant START = Instant.parse("2026-11-18T20:31:47Z");
    private static final LocalDateTime CLOSED_SINCE = LocalDateTime.of(2026, 11, 18, 19, 46, 36);
    private static final GarageGate KATE = new GarageGate(148, "Kate", "192.0.2.79", 0, List.of(69L));

    private final GateRegistry gateRegistry = mock(GateRegistry.class);
    private final GarageSensors garageSensors = mock(GarageSensors.class);
    private final ShellyGen1Client shelly = mock(ShellyGen1Client.class);
    private final GateCommandRepository repository = mock(GateCommandRepository.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(START);
    private final GaragePulser pulser = new GaragePulser(gateRegistry, garageSensors, shelly, repository, new Clock() {
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
        when(gateRegistry.garage(148)).thenReturn(Optional.of(new GateRegistry.GarageGateState(KATE, 1.0, true, null)));
        when(shelly.relay(anyString(), anyInt(), anyBoolean())).thenReturn(Mono.empty());
        when(repository.save(any())).thenAnswer(saved -> Mono.just(saved.getArgument(0)));
        when(garageSensors.stateOf(KATE)).thenReturn(reading(GarageState.CLOSED, CLOSED_SINCE));
    }

    private static GarageSensors.GarageReading reading(final GarageState state, final LocalDateTime since) {
        return new GarageSensors.GarageReading(state, since, null);
    }

    @Test
    void should_send_one_pulse_and_log_it_with_its_gate() {
        pulser.pulse(148, "Anna").as(StepVerifier::create).verifyComplete();

        verify(shelly).relay("192.0.2.79", 0, true);
        verify(repository).save(GateCommandEntity.ofGarage(148, LocalDateTime.ofInstant(START, HOUSE), "DASHBOARD", "Anna",
            GateCommandEntity.PULSED));
    }

    //no repeat window: two presses are two pulses
    @Test
    void should_send_every_pulse_at_once() {
        pulser.pulse(148, "Anna").as(StepVerifier::create).verifyComplete();
        now.set(START.plusSeconds(2));
        pulser.pulse(148, "Anna").as(StepVerifier::create).verifyComplete();

        verify(shelly, times(2)).relay("192.0.2.79", 0, true);
    }

    @Test
    void should_confirm_a_pulse_its_sensor_answered_and_not_one_it_did_not() {
        assertThat(pulser.lastCommandConfirmed(KATE)).isEmpty();
        pulser.pulse(148, "Anna").as(StepVerifier::create).verifyComplete();

        now.set(START.plusSeconds(30));
        assertThat(pulser.lastCommandConfirmed(KATE)).isEmpty();
        now.set(START.plusSeconds(61));
        assertThat(pulser.lastCommandConfirmed(KATE)).contains(false);

        when(garageSensors.stateOf(KATE)).thenReturn(reading(GarageState.OPEN, LocalDateTime.of(2026, 11, 18, 21, 31, 54)));
        assertThat(pulser.lastCommandConfirmed(KATE)).contains(true);
    }

    //a second pulse within the minute cancels the check - a gate stopped half-way reports nothing new
    @Test
    void should_cancel_the_check_with_a_second_pulse_within_the_minute() {
        pulser.pulse(148, "Anna").as(StepVerifier::create).verifyComplete();
        now.set(START.plusSeconds(5));
        pulser.pulse(148, "Anna").as(StepVerifier::create).verifyComplete();
        now.set(START.plusSeconds(120));

        assertThat(pulser.lastCommandConfirmed(KATE)).isEmpty();
    }

    @Test
    void should_refuse_a_gate_it_does_not_know_or_cannot_command_safely() {
        when(gateRegistry.garage(147)).thenReturn(Optional.empty());
        when(gateRegistry.garage(149)).thenReturn(Optional.of(new GateRegistry.GarageGateState(
            new GarageGate(149, "Kris", "192.0.2.29", 0, List.of(70L)), 0.0, true, null)));

        pulser.pulse(147, "Anna").as(StepVerifier::create).verifyError(GateException.GateNotFound.class);
        pulser.pulse(149, "Anna").as(StepVerifier::create).verifyError(GateException.GateUnavailable.class);

        verify(shelly, never()).relay(anyString(), anyInt(), anyBoolean());
        verify(repository).save(GateCommandEntity.ofGarage(149, LocalDateTime.ofInstant(START, HOUSE), "DASHBOARD", "Anna",
            GateCommandEntity.UNAVAILABLE));
    }

    //no answer: it may have moved - never retried, logged as failed, and a failed log never fails the pulse
    @Test
    void should_report_a_shelly_that_did_not_take_the_pulse() {
        when(shelly.relay(anyString(), anyInt(), anyBoolean())).thenReturn(Mono.error(new IllegalStateException("timeout")));
        doReturn(Mono.error(new IllegalStateException("database down"))).when(repository).save(any());

        pulser.pulse(148, "Anna").as(StepVerifier::create).verifyError(GateException.DeviceFailed.class);

        verify(shelly, times(1)).relay("192.0.2.79", 0, true);
    }
}
