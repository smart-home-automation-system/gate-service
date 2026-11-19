package cloud.cholewa.gate.garage;

import cloud.cholewa.gate.database.GarageGateChangeEntity;
import cloud.cholewa.gate.database.GarageGateChangeRepository;
import cloud.cholewa.gate.gate.GarageGate;
import cloud.cholewa.gate.gate.GateRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//HAS-474: a change of a sensor is a row for every garage gate that names it, in the time of the house; never a failure
class GarageChangeLogTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-18T20:35:00Z"), HOUSE);
    private static final Instant OPENED = Instant.parse("2026-11-18T20:31:54Z");
    private static final Instant CLOSED = Instant.parse("2026-11-18T20:34:23Z");

    private final GateRegistry gateRegistry = mock(GateRegistry.class);
    private final GarageGateChangeRepository repository = mock(GarageGateChangeRepository.class);
    private final GarageChangeLog log = new GarageChangeLog(gateRegistry, repository, CLOCK);

    {
        when(gateRegistry.garages()).thenReturn(List.of(
            new GateRegistry.GarageGateState(new GarageGate(148, "Kate", "192.0.2.79", 0, List.of(69L)), 1.0, true, null),
            new GateRegistry.GarageGateState(new GarageGate(149, "Kris", "192.0.2.29", 0, List.of(70L)), 1.0, true, null)));
        when(repository.save(any())).thenAnswer(saved -> Mono.just(saved.getArgument(0)));
    }

    @Test
    void should_log_a_closing_with_since_when_the_gate_stood_open() {
        log.record(69L, false, CLOSED, OPENED).as(StepVerifier::create).verifyComplete();

        verify(repository).save(GarageGateChangeEntity.of(148, LocalDateTime.of(2026, 11, 18, 21, 34, 23), false,
            LocalDateTime.of(2026, 11, 18, 21, 31, 54)));
    }

    //an opening has no since; a time the gateway did not give is now
    @Test
    void should_log_an_opening_at_now_when_the_gateway_gave_no_time() {
        log.record(70L, true, null, OPENED).as(StepVerifier::create).verifyComplete();

        verify(repository).save(GarageGateChangeEntity.of(149, LocalDateTime.of(2026, 11, 18, 21, 35), true, null));
    }

    @Test
    void should_log_nothing_for_a_sensor_no_gate_names_and_never_fail() {
        log.record(61L, true, OPENED, null).as(StepVerifier::create).verifyComplete();
        verify(repository, never()).save(any());

        doReturn(Mono.error(new IllegalStateException("database down"))).when(repository).save(any());
        log.record(69L, true, OPENED, null).as(StepVerifier::create).verifyComplete();
    }
}
