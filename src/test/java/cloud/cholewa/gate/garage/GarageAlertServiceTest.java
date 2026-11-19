package cloud.cholewa.gate.garage;

import cloud.cholewa.gate.api.model.GarageAlertReply;
import cloud.cholewa.gate.database.GarageGateAlertEntity;
import cloud.cholewa.gate.database.GarageGateAlertRepository;
import cloud.cholewa.gate.gate.GarageGate;
import cloud.cholewa.gate.gate.GateRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

//HAS-454: the open episodes of the alarms, the newest first, in the words of the dashboard - an alarm for cold and the
//empty house, a warning for long; a gate the registry no longer knows keeps its id and loses its name
class GarageAlertServiceTest {

    private static final LocalDateTime OPEN = LocalDateTime.of(2026, 11, 19, 7, 40);

    private final GarageGateAlertRepository repository = mock(GarageGateAlertRepository.class);
    private final GateRegistry gateRegistry = mock(GateRegistry.class);
    private final GarageAlertService service = new GarageAlertService(repository, gateRegistry);

    @Test
    void should_answer_the_open_episodes_newest_first_in_the_words_of_the_dashboard() {
        when(gateRegistry.garage(148)).thenReturn(Optional.of(new GateRegistry.GarageGateState(
            new GarageGate(148, "Kate", "192.0.2.79", 0, List.of(69L)), 1.0, true, null)));
        when(gateRegistry.garage(149)).thenReturn(Optional.empty());
        when(repository.findByClosedAtIsNull()).thenReturn(Flux.just(
            new GarageGateAlertEntity(1L, 148L, GarageAlarmEvaluator.LONG, OPEN, OPEN.plusMinutes(30),
                OPEN.plusMinutes(30), 0, null),
            new GarageGateAlertEntity(2L, 148L, GarageAlarmEvaluator.COLD, OPEN, OPEN.plusMinutes(15),
                OPEN.plusMinutes(45), 1, null),
            new GarageGateAlertEntity(3L, 149L, GarageAlarmEvaluator.EMPTY_HOUSE, OPEN, OPEN.plusMinutes(40),
                OPEN.plusMinutes(40), 0, null)));

        service.open().as(StepVerifier::create)
            .expectNext(new GarageAlertReply(149, null, "empty house", "alarm", OPEN, OPEN.plusMinutes(40), 0))
            .expectNext(new GarageAlertReply(148, "Kate", "long", "warning", OPEN, OPEN.plusMinutes(30), 0))
            .expectNext(new GarageAlertReply(148, "Kate", "cold", "alarm", OPEN, OPEN.plusMinutes(15), 1))
            .verifyComplete();
    }
}
