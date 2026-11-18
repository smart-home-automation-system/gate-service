package cloud.cholewa.gate.gate;

import cloud.cholewa.gate.registry.DeviceRegistryClient;
import cloud.cholewa.gate.registry.RegistryCallException;
import cloud.cholewa.home.model.DeviceKind;
import cloud.cholewa.home.model.ShellyBinding;
import cloud.cholewa.home.model.SmartDevice;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GateRegistryTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-18T08:00:00Z"), ZoneId.of("Europe/Warsaw"));
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 18, 9, 0);

    private final DeviceRegistryClient registryClient = mock(DeviceRegistryClient.class);
    private final GateSettingsClient settingsClient = mock(GateSettingsClient.class);
    private final GateRegistry registry = new GateRegistry(registryClient, settingsClient, CLOCK);

    private static SmartDevice gate(final long id, final String address, final Integer channel) {
        return SmartDevice.builder().id(id).name("Gate").kind(DeviceKind.GATE).enabled(true)
            .shelly(ShellyBinding.builder().address(address).generation(1).channel(channel).build()).build();
    }

    @Test
    void should_know_nothing_before_the_first_read() {
        assertThat(registry.current()).isEmpty();
    }

    @Test
    void should_keep_the_gate_of_the_registry_with_the_auto_off_of_its_relay() {
        when(registryClient.getGates()).thenReturn(Mono.just(List.of(gate(147, "192.0.2.85", 0))));
        when(settingsClient.autoOff("192.0.2.85", 0)).thenReturn(Mono.just(1.0));

        registry.refreshOnce().as(StepVerifier::create).verifyComplete();

        assertThat(registry.current()).contains(
            new GateRegistry.GateState(new GateDevice(147, "192.0.2.85", 0), 1.0, true, NOW));
        assertThat(registry.current().orElseThrow().safe()).isTrue();
    }

    //a device that does not answer is not safe to command: its auto_off is not known
    @Test
    void should_make_a_silent_gate_unreachable_and_not_safe() {
        when(registryClient.getGates()).thenReturn(Mono.just(List.of(gate(147, "192.0.2.85", 0))));
        when(settingsClient.autoOff(anyString(), anyInt())).thenReturn(Mono.error(new IllegalStateException("timeout")));

        registry.refreshOnce().as(StepVerifier::create).verifyComplete();

        assertThat(registry.current()).hasValueSatisfying(state -> {
            assertThat(state.reachable()).isFalse();
            assertThat(state.autoOff()).isNull();
            assertThat(state.safe()).isFalse();
        });
    }

    @Test
    void should_never_take_a_relay_that_does_not_switch_itself_off_for_safe() {
        when(registryClient.getGates()).thenReturn(Mono.just(List.of(gate(147, "192.0.2.85", 0))));
        when(settingsClient.autoOff(anyString(), anyInt())).thenReturn(Mono.just(0.0));

        registry.refreshOnce().as(StepVerifier::create).verifyComplete();

        assertThat(registry.current().orElseThrow().safe()).isFalse();
    }

    //a failed read of the registry keeps the gate read before
    @Test
    void should_keep_the_gate_when_the_registry_cannot_be_read() {
        when(registryClient.getGates()).thenReturn(Mono.just(List.of(gate(147, "192.0.2.85", 0))));
        when(settingsClient.autoOff(anyString(), anyInt())).thenReturn(Mono.just(1.0));
        registry.refreshOnce().as(StepVerifier::create).verifyComplete();

        when(registryClient.getGates()).thenReturn(Mono.error(new RegistryCallException("database-service answered 503")));
        registry.refreshOnce().as(StepVerifier::create).verifyComplete();

        assertThat(registry.current()).map(state -> state.device().id()).contains(147L);
    }

    //a gate removed from the registry, or one the service cannot drive, is no gate
    @Test
    void should_forget_the_gate_the_registry_no_longer_has() {
        when(registryClient.getGates()).thenReturn(Mono.just(List.of(gate(147, "192.0.2.85", 0))));
        when(settingsClient.autoOff(anyString(), anyInt())).thenReturn(Mono.just(1.0));
        registry.refreshOnce().as(StepVerifier::create).verifyComplete();

        when(registryClient.getGates()).thenReturn(Mono.just(List.of(gate(148, "192.0.2.86", null))));
        registry.refreshOnce().as(StepVerifier::create).verifyComplete();

        assertThat(registry.current()).isEmpty();
    }

    @Test
    void should_drive_the_gate_of_the_lowest_id_of_more() {
        when(registryClient.getGates()).thenReturn(Mono.just(List.of(gate(150, "192.0.2.90", 0), gate(147, "192.0.2.85", 0))));
        when(settingsClient.autoOff(anyString(), anyInt())).thenReturn(Mono.just(1.0));

        registry.refreshOnce().as(StepVerifier::create).verifyComplete();

        assertThat(registry.current()).map(state -> state.device().id()).contains(147L);
    }
}
