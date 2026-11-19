package cloud.cholewa.gate.garage;

import cloud.cholewa.gate.api.model.GarageGateReply;
import cloud.cholewa.gate.error.GateException;
import cloud.cholewa.gate.gate.GarageGate;
import cloud.cholewa.gate.gate.GateRegistry;
import cloud.cholewa.gate.gate.HouseholdMembers;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//HAS-473: the gates of the registry with what their sensors say - memory only
class GarageGateServiceTest {

    private static final LocalDateTime SINCE = LocalDateTime.of(2026, 11, 18, 19, 44, 57);
    private static final LocalDateTime CONFIRMED = LocalDateTime.of(2026, 11, 18, 19, 50);
    private static final GarageGate KATE = new GarageGate(148, "Kate", "192.0.2.79", 0, List.of(69L));
    private static final GarageGate KRIS = new GarageGate(149, "Kris", "192.0.2.29", 0, List.of(70L));

    private final GateRegistry gateRegistry = mock(GateRegistry.class);
    private final GarageSensors garageSensors = mock(GarageSensors.class);
    private final GaragePulser garagePulser = mock(GaragePulser.class);
    private final HouseholdMembers householdMembers = mock(HouseholdMembers.class);
    private final GarageGateService service = new GarageGateService(gateRegistry, garageSensors, garagePulser,
        householdMembers);

    {
        //no pulse since the start: the answer leaves the field out
        when(garagePulser.lastCommandConfirmed(any())).thenReturn(Optional.empty());
    }

    @Test
    void should_answer_every_garage_gate_with_what_its_sensors_say() {
        when(gateRegistry.garages()).thenReturn(List.of(
            new GateRegistry.GarageGateState(KATE, 1.0, true, CONFIRMED),
            new GateRegistry.GarageGateState(KRIS, null, false, CONFIRMED)));
        when(garageSensors.stateOf(KATE)).thenReturn(new GarageSensors.GarageReading(GarageState.OPEN, SINCE, CONFIRMED));
        when(garageSensors.stateOf(KRIS)).thenReturn(new GarageSensors.GarageReading(GarageState.UNKNOWN, null, null));

        service.all().as(StepVerifier::create)
            .expectNext(new GarageGateReply(148, "Kate", GarageState.OPEN, SINCE, CONFIRMED, true, true, null))
            .expectNext(new GarageGateReply(149, "Kris", GarageState.UNKNOWN, null, null, false, false, null))
            .verifyComplete();
    }

    //HAS-474: the member checked first, as the registry spells the name; an unknown one sends nothing
    @Test
    void should_pulse_for_a_known_member_only() {
        when(householdMembers.known("anna")).thenReturn(Mono.just("Anna"));
        when(householdMembers.known("Nobody")).thenReturn(Mono.error(new GateException.UnknownMember("Nobody")));
        when(garagePulser.pulse(148, "Anna")).thenReturn(Mono.empty());

        service.pulse(148, "anna").as(StepVerifier::create).verifyComplete();
        service.pulse(148, "Nobody").as(StepVerifier::create).verifyError(GateException.UnknownMember.class);

        verify(garagePulser).pulse(148, "Anna");
        verify(garagePulser, never()).pulse(148, "Nobody");
    }

    @Test
    void should_answer_one_garage_gate_and_refuse_an_id_that_is_none() {
        when(gateRegistry.garage(148)).thenReturn(Optional.of(new GateRegistry.GarageGateState(KATE, 1.0, true, CONFIRMED)));
        when(gateRegistry.garage(147)).thenReturn(Optional.empty());
        when(garageSensors.stateOf(KATE)).thenReturn(new GarageSensors.GarageReading(GarageState.CLOSED, SINCE, CONFIRMED));

        service.one(148).as(StepVerifier::create)
            .expectNext(new GarageGateReply(148, "Kate", GarageState.CLOSED, SINCE, CONFIRMED, true, true, null))
            .verifyComplete();
        service.one(147).as(StepVerifier::create).verifyError(GateException.GateNotFound.class);
    }
}
