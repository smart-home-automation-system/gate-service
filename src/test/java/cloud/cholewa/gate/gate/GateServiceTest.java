package cloud.cholewa.gate.gate;

import cloud.cholewa.gate.api.model.GateReply;
import cloud.cholewa.gate.database.GateCommandEntity;
import cloud.cholewa.gate.database.GateCommandRepository;
import cloud.cholewa.gate.database.IntercomEventEntity;
import cloud.cholewa.gate.database.IntercomEventRepository;
import cloud.cholewa.gate.error.GateException;
import cloud.cholewa.gate.intercom.BridgeAvailability;
import cloud.cholewa.gate.registry.HouseholdProfilesClient;
import cloud.cholewa.gate.registry.RegistryCallException;
import cloud.cholewa.home.model.HouseholdProfile;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GateServiceTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 18, 9, 25);

    private final HouseholdProfilesClient profiles = mock(HouseholdProfilesClient.class);
    private final GateOpener opener = mock(GateOpener.class);
    private final GateRegistry gateRegistry = mock(GateRegistry.class);
    private final BridgeAvailability bridge = new BridgeAvailability();
    private final IntercomEventRepository events = mock(IntercomEventRepository.class);
    private final GateCommandRepository commands = mock(GateCommandRepository.class);
    private final GateService service = new GateService(new HouseholdMembers(profiles), opener, gateRegistry, bridge,
        events, commands);

    private static HouseholdProfile profile(final String name) {
        return HouseholdProfile.builder().name(name).build();
    }

    //the member as the registry spells the name, whatever the case of the request
    @Test
    void should_open_the_gate_for_a_member_of_the_household() {
        when(profiles.getProfiles()).thenReturn(Mono.just(List.of(profile("Anna"), profile("Jan"))));
        when(opener.open(any(), any())).thenReturn(Mono.empty());

        service.openFromDashboard("anna").as(StepVerifier::create).verifyComplete();

        verify(opener).open(GateOpener.Source.DASHBOARD, "Anna");
    }

    @Test
    void should_refuse_a_name_that_is_no_member() {
        when(profiles.getProfiles()).thenReturn(Mono.just(List.of(profile("Anna"))));

        service.openFromDashboard("Borys").as(StepVerifier::create).verifyError(GateException.UnknownMember.class);

        verify(opener, never()).open(any(), any());
    }

    //a member who cannot be checked opens nothing
    @Test
    void should_refuse_when_the_profiles_cannot_be_read() {
        when(profiles.getProfiles()).thenReturn(Mono.error(new RegistryCallException("database-service answered 503")));

        service.openFromDashboard("Anna").as(StepVerifier::create).verifyError(GateException.RegistryUnavailable.class);

        verify(opener, never()).open(any(), any());
    }

    //a refusal of the opener reaches the caller as it is
    @Test
    void should_pass_on_a_refusal_of_the_opener() {
        when(profiles.getProfiles()).thenReturn(Mono.just(List.of(profile("Anna"))));
        when(opener.open(any(), any())).thenReturn(Mono.error(new GateException.OpenTooSoon(15)));

        service.openFromDashboard("Anna").as(StepVerifier::create).verifyError(GateException.OpenTooSoon.class);
    }

    @Test
    void should_read_the_gate_the_bridge_the_last_ring_and_the_last_opening() {
        when(gateRegistry.current()).thenReturn(Optional.of(
            new GateRegistry.GateState(new GateDevice(147, "Gate", "192.0.2.85", 0), 1.0, true, AT)));
        bridge.report("Call-state", "online", AT);
        when(events.findFirstByKindOrderByOccurredAtDesc(IntercomEventEntity.RING))
            .thenReturn(Mono.just(IntercomEventEntity.ring(AT.minusMinutes(3), "10010100000")));
        when(commands.findFirstByOutcomeOrderByAtDesc(GateCommandEntity.OPENED))
            .thenReturn(Mono.just(GateCommandEntity.of(AT.minusMinutes(1), "DASHBOARD", "Anna", GateCommandEntity.OPENED)));

        service.read().as(StepVerifier::create)
            .assertNext(reply -> assertThat(reply).isEqualTo(new GateReply(
                new GateReply.Gate(147L, true, true, 1.0, AT),
                new GateReply.Bridge(true, AT),
                new GateReply.Ring(AT.minusMinutes(3), "10010100000"),
                new GateReply.Opening(AT.minusMinutes(1), "dashboard", "Anna"))))
            .verifyComplete();
    }

    //what is not known is left out, never defaulted
    @Test
    void should_leave_out_what_is_not_known() {
        when(gateRegistry.current()).thenReturn(Optional.empty());
        when(events.findFirstByKindOrderByOccurredAtDesc(any())).thenReturn(Mono.empty());
        when(commands.findFirstByOutcomeOrderByAtDesc(any())).thenReturn(Mono.empty());

        service.read().as(StepVerifier::create)
            .assertNext(reply -> assertThat(reply).isEqualTo(new GateReply(null, null, null, null)))
            .verifyComplete();
    }
}
