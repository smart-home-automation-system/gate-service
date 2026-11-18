package cloud.cholewa.gate.gate;

import cloud.cholewa.gate.config.GateProperties;
import cloud.cholewa.gate.database.GateCommandEntity;
import cloud.cholewa.gate.database.GateCommandRepository;
import cloud.cholewa.gate.error.GateException;
import cloud.cholewa.shelly.http.ShellyGen1Client;
import cloud.cholewa.shelly.model.Relay;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GateOpenerTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    private static final Instant START = Instant.parse("2026-11-18T08:00:00Z");
    private static final GateDevice GATE = new GateDevice(147L, "192.0.2.85", 0);
    private static final GateRegistry.GateState SAFE =
        new GateRegistry.GateState(GATE, 1.0, true, LocalDateTime.of(2026, 11, 18, 8, 55));
    private static final GateProperties PROPERTIES = new GateProperties(List.of("10010110001"),
        new GateProperties.Open(Duration.ofSeconds(15)), new GateProperties.Screen(Duration.ofSeconds(60)));

    private final GateRegistry gateRegistry = mock(GateRegistry.class);
    private final ShellyGen1Client shelly = mock(ShellyGen1Client.class);
    private final GateCommandRepository repository = mock(GateCommandRepository.class);
    private final MovableClock clock = new MovableClock(START);
    private final GateOpener opener = new GateOpener(gateRegistry, shelly, repository, PROPERTIES, clock);

    @BeforeEach
    void setUp() {
        when(gateRegistry.current()).thenReturn(Optional.of(SAFE));
        when(shelly.relay(anyString(), anyInt(), anyBoolean())).thenReturn(Mono.just(mock(Relay.class)));
        when(repository.save(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    }

    //one "turn on" of the relay of its channel - the auto_off of the relay ends the pulse
    @Test
    void should_turn_the_relay_of_the_gate_on_once_and_log_the_opening() {
        opener.open(GateOpener.Source.DASHBOARD, "Anna").as(StepVerifier::create).verifyComplete();

        verify(shelly).relay("192.0.2.85", 0, true);
        assertThat(logged()).containsExactly(
            GateCommandEntity.of(LocalDateTime.of(2026, 11, 18, 9, 0), "DASHBOARD", "Anna", GateCommandEntity.OPENED));
    }

    //the gate is step-by-step: a second pulse within 15 s would stop or reverse it - refused, and logged
    @Test
    void should_refuse_a_second_opening_within_the_repeat_and_take_one_after_it() {
        opener.open(GateOpener.Source.SCREEN, null).as(StepVerifier::create).verifyComplete();
        clock.moveBy(Duration.ofSeconds(14));
        opener.open(GateOpener.Source.DASHBOARD, "Anna").as(StepVerifier::create)
            .verifyError(GateException.OpenTooSoon.class);
        clock.moveBy(Duration.ofSeconds(1));
        opener.open(GateOpener.Source.DASHBOARD, "Anna").as(StepVerifier::create).verifyComplete();

        verify(shelly, times(2)).relay("192.0.2.85", 0, true);
        assertThat(logged()).extracting(GateCommandEntity::outcome)
            .containsExactly(GateCommandEntity.OPENED, GateCommandEntity.TOO_SOON, GateCommandEntity.OPENED);
    }

    //no answer means it may have moved: never retried, and the next opening still waits out the repeat
    @Test
    void should_send_a_failed_command_once_and_count_it_as_sent() {
        when(shelly.relay(anyString(), anyInt(), anyBoolean())).thenReturn(Mono.error(new IllegalStateException("timeout")));

        opener.open(GateOpener.Source.DASHBOARD, "Anna").as(StepVerifier::create)
            .verifyError(GateException.DeviceFailed.class);
        opener.open(GateOpener.Source.DASHBOARD, "Anna").as(StepVerifier::create)
            .verifyError(GateException.OpenTooSoon.class);

        verify(shelly, times(1)).relay(anyString(), anyInt(), anyBoolean());
        assertThat(logged()).extracting(GateCommandEntity::outcome)
            .containsExactly(GateCommandEntity.FAILED, GateCommandEntity.TOO_SOON);
    }

    //a relay that does not switch itself off would give the drive a permanent signal; a gate never read is not known
    @Test
    void should_send_nothing_while_the_gate_is_not_known_or_not_safe() {
        when(gateRegistry.current()).thenReturn(Optional.empty());
        opener.open(GateOpener.Source.SCREEN, null).as(StepVerifier::create)
            .verifyError(GateException.GateUnavailable.class);

        when(gateRegistry.current()).thenReturn(Optional.of(new GateRegistry.GateState(GATE, 0.0, true, null)));
        opener.open(GateOpener.Source.SCREEN, null).as(StepVerifier::create)
            .verifyError(GateException.GateUnavailable.class);

        when(gateRegistry.current()).thenReturn(Optional.of(new GateRegistry.GateState(GATE, null, false, null)));
        opener.open(GateOpener.Source.SCREEN, null).as(StepVerifier::create)
            .verifyError(GateException.GateUnavailable.class);

        verify(shelly, never()).relay(anyString(), anyInt(), anyBoolean());
        assertThat(logged()).extracting(GateCommandEntity::outcome).containsOnly(GateCommandEntity.UNAVAILABLE);
    }

    //the log of the command never fails the opening - the gate has moved
    @Test
    void should_open_the_gate_when_its_log_cannot_be_written() {
        doReturn(Mono.error(new IllegalStateException("database down"))).when(repository).save(any());

        opener.open(GateOpener.Source.SCREEN, null).as(StepVerifier::create).verifyComplete();

        verify(shelly).relay("192.0.2.85", 0, true);
    }

    private List<GateCommandEntity> logged() {
        final ArgumentCaptor<GateCommandEntity> rows = ArgumentCaptor.forClass(GateCommandEntity.class);
        verify(repository, atLeastOnce()).save(rows.capture());
        return rows.getAllValues();
    }

    static final class MovableClock extends Clock {

        private Instant now;

        MovableClock(final Instant now) {
            this.now = now;
        }

        void moveBy(final Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return HOUSE;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
