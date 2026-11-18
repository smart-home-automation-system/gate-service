package cloud.cholewa.gate.garage;

import cloud.cholewa.gate.config.GarageProperties;
import cloud.cholewa.gate.gate.GarageGate;
import cloud.cholewa.gate.gate.GateRegistry;
import cloud.cholewa.gate.registry.DeviceRegistryClient;
import cloud.cholewa.gate.registry.RegistryCallException;
import cloud.cholewa.home.model.BthomeBinding;
import cloud.cholewa.home.model.DeviceKind;
import cloud.cholewa.home.model.ShellyBinding;
import cloud.cholewa.home.model.SmartDevice;
import cloud.cholewa.shelly.http.ShellyRpcClient;
import cloud.cholewa.shelly.model.BthomeSensorStatus;
import cloud.cholewa.shelly.model.ShellyComponent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//HAS-473: the state of a garage gate from the BLU sensor on it, as the BTHome gateway GRID POWER holds it
class GarageSensorsTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    private static final Instant START = Instant.parse("2026-11-18T18:00:00Z");
    private static final String GATEWAY = "shellypro3em-0123456789ab";
    //the BLU sensors of the two gates, on the components 202 and 206 of the gateway
    private static final String KATE = "8c:6f:b9:00:00:69";
    private static final String KRIS = "3c:2e:f5:00:00:70";
    private static final GarageGate KATE_GATE = new GarageGate(148, "Kate", "192.0.2.79", 0, List.of(69L));
    private static final GarageGate KRIS_GATE = new GarageGate(149, "Kris", "192.0.2.29", 0, List.of(70L));

    private final DeviceRegistryClient registryClient = mock(DeviceRegistryClient.class);
    private final GateRegistry gateRegistry = mock(GateRegistry.class);
    private final ShellyRpcClient rpcClient = mock(ShellyRpcClient.class);
    private final GarageSensorStore store = mock(GarageSensorStore.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(START);
    private final GarageSensors sensors = new GarageSensors(registryClient, gateRegistry, rpcClient,
        JsonMapper.builder().build(), new GarageProperties(Duration.ofMinutes(30)), store, new Clock() {
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
        when(gateRegistry.garages()).thenReturn(List.of(
            new GateRegistry.GarageGateState(KATE_GATE, 1.0, true, LocalDateTime.now(HOUSE)),
            new GateRegistry.GarageGateState(KRIS_GATE, 1.0, true, LocalDateTime.now(HOUSE))));
        when(registryClient.getOpeningSensors()).thenReturn(Mono.just(List.of(
            sensor(69, KATE), sensor(70, KRIS), sensor(61, "38:39:8f:00:00:61"))));
        when(registryClient.getBthomeGateways()).thenReturn(Mono.just(List.of(SmartDevice.builder().id(79L)
            .name("Grid power").kind(DeviceKind.BTHOME_GATEWAY).enabled(true)
            .shelly(ShellyBinding.builder().address("192.0.2.95").generation(2).deviceId(GATEWAY.toUpperCase()).build())
            .build())));
        when(store.save(anyLong(), any())).thenReturn(Mono.empty());
        when(store.retain(any())).thenReturn(Mono.empty());
        when(store.load()).thenReturn(Flux.empty());
    }

    private static SmartDevice sensor(final long id, final String address) {
        return SmartDevice.builder().id(id).name("Sensor " + id).kind(DeviceKind.OPENING_SENSOR).enabled(true)
            .bthome(BthomeBinding.builder().address(address).build()).build();
    }

    //a bthomesensor of the gateway: its window object and what it holds
    private static ShellyComponent window(final int id, final String address, final Boolean open, final Instant heard) {
        final ShellyComponent component = new ShellyComponent();
        component.setKey("bthomesensor:" + id);
        component.setConfig(Map.of("id", id, "addr", address, "obj_id", 45, "idx", 0));
        component.setStatus(open == null
            ? Map.of("id", id)
            : Map.of("id", id, "value", open, "last_updated_ts", heard.getEpochSecond()));
        return component;
    }

    private void gatewayHolds(final ShellyComponent... components) {
        when(rpcClient.dynamicComponents("192.0.2.95")).thenReturn(Mono.just(List.of(components)));
    }

    private static BthomeSensorStatus status(final int id, final boolean open, final Instant heard) {
        final BthomeSensorStatus status = new BthomeSensorStatus();
        status.setId(id);
        status.setValue(open);
        status.setLastUpdatedTs(heard.getEpochSecond());
        return status;
    }

    private static LocalDateTime house(final Instant instant) {
        return LocalDateTime.ofInstant(instant, HOUSE);
    }

    @Test
    void should_know_nothing_before_the_first_read() {
        assertThat(sensors.stateOf(KATE_GATE).state()).isEqualTo(GarageState.UNKNOWN);
        assertThat(sensors.stateOf(KATE_GATE).since()).isNull();
    }

    //the gateway reads both gates, since when each is in its state - the time the gateway heard it
    @Test
    void should_read_the_state_of_each_gate_from_its_gateway() {
        final Instant closedAt = START.minusSeconds(3600);
        final Instant openedAt = START.minusSeconds(600);
        gatewayHolds(window(202, KATE, false, closedAt), window(206, KRIS, true, openedAt));

        sensors.refresh().as(StepVerifier::create).verifyComplete();

        assertThat(sensors.stateOf(KATE_GATE)).isEqualTo(
            new GarageSensors.GarageReading(GarageState.CLOSED, house(closedAt), house(START)));
        assertThat(sensors.stateOf(KRIS_GATE)).isEqualTo(
            new GarageSensors.GarageReading(GarageState.OPEN, house(openedAt), house(START)));
        assertThat(sensors.knows(GATEWAY, 202)).isTrue();
        assertThat(sensors.knows(GATEWAY.toUpperCase(), 206)).isTrue();
        verify(store).save(69L, new GarageSensors.Reading(false, closedAt, house(START)));
    }

    //between two reads the status topic says it: opening the gate is seen at once, and stored
    @Test
    void should_take_a_change_from_the_status_topic() {
        gatewayHolds(window(202, KATE, false, START.minusSeconds(3600)));
        sensors.refresh().as(StepVerifier::create).verifyComplete();
        now.set(START.plusSeconds(60));

        sensors.observe(GATEWAY, 202, status(202, true, START.plusSeconds(58))).as(StepVerifier::create).verifyComplete();

        assertThat(sensors.stateOf(KATE_GATE).state()).isEqualTo(GarageState.OPEN);
        assertThat(sensors.stateOf(KATE_GATE).since()).isEqualTo(house(START.plusSeconds(58)));
        verify(store).save(69L, new GarageSensors.Reading(true, START.plusSeconds(58), house(START.plusSeconds(60))));
    }

    //a BLU sensor sends only when it changes: its state stands while the gateway stands for it, not known after
    @Test
    void should_make_a_gate_not_known_when_its_gateway_went_silent() {
        gatewayHolds(window(202, KATE, false, START.minusSeconds(3600)));
        sensors.refresh().as(StepVerifier::create).verifyComplete();
        when(rpcClient.dynamicComponents("192.0.2.95")).thenReturn(Mono.error(new IllegalStateException("timeout")));

        now.set(START.plus(Duration.ofMinutes(29)));
        sensors.refresh().as(StepVerifier::create).verifyComplete();
        assertThat(sensors.stateOf(KATE_GATE).state()).isEqualTo(GarageState.CLOSED);

        now.set(START.plus(Duration.ofMinutes(31)));
        assertThat(sensors.stateOf(KATE_GATE).state()).isEqualTo(GarageState.UNKNOWN);
    }

    //a gateway restarted brings back an older state with its old time: the newer one stays
    @Test
    void should_keep_the_newer_reading_when_the_gateway_gives_an_older_one() {
        gatewayHolds(window(202, KATE, false, START.minusSeconds(60)));
        sensors.refresh().as(StepVerifier::create).verifyComplete();
        gatewayHolds(window(202, KATE, true, START.minusSeconds(120)));

        sensors.refresh().as(StepVerifier::create).verifyComplete();

        assertThat(sensors.stateOf(KATE_GATE).state()).isEqualTo(GarageState.CLOSED);
    }

    //a stored reading is read back not confirmed: not known until the gateway stands for it
    @Test
    void should_restore_the_stored_readings_as_not_known() {
        when(store.load()).thenReturn(Flux.just(new GarageSensorStore.Stored(69L,
            new GarageSensors.Reading(false, START.minusSeconds(3600), null))));

        sensors.restore().as(StepVerifier::create).verifyComplete();
        assertThat(sensors.stateOf(KATE_GATE).state()).isEqualTo(GarageState.UNKNOWN);

        gatewayHolds(window(202, KATE, false, START.minusSeconds(3600)));
        sensors.refresh().as(StepVerifier::create).verifyComplete();
        assertThat(sensors.stateOf(KATE_GATE).state()).isEqualTo(GarageState.CLOSED);
        //the same value heard at the same time is no change: nothing written again
        verify(store, never()).save(anyLong(), any());
    }

    //before the gates are known (their registry not read since the start) no pass runs: the restored readings stay
    @Test
    void should_run_no_pass_before_the_gates_are_known() {
        when(gateRegistry.garages()).thenReturn(List.of());
        when(store.load()).thenReturn(Flux.just(new GarageSensorStore.Stored(69L,
            new GarageSensors.Reading(false, START.minusSeconds(3600), null))));
        sensors.restore().as(StepVerifier::create).verifyComplete();

        sensors.refresh().as(StepVerifier::create).verifyComplete();

        verify(registryClient, never()).getOpeningSensors();
        verify(rpcClient, never()).dynamicComponents(any());
        when(gateRegistry.garages()).thenReturn(List.of(
            new GateRegistry.GarageGateState(KATE_GATE, 1.0, true, LocalDateTime.now(HOUSE))));
        gatewayHolds(window(202, KATE, true, START.minusSeconds(7200)));
        sensors.refresh().as(StepVerifier::create).verifyComplete();
        //the older open of the restarted gateway loses to the restored closed
        assertThat(sensors.stateOf(KATE_GATE).state()).isEqualTo(GarageState.CLOSED);
    }

    //a sensor no gate names (a window of blinds behind the same gateway) is not this service's
    @Test
    void should_leave_the_sensors_of_no_garage_gate_alone() {
        gatewayHolds(window(210, "38:39:8f:00:00:61", true, START), window(202, KATE, false, START));

        sensors.refresh().as(StepVerifier::create).verifyComplete();

        assertThat(sensors.knows(GATEWAY, 210)).isFalse();
        verify(store).retain(Set.of(69L, 70L));
    }

    //a registry away keeps the sensors read before, and the gateway is read all the same
    @Test
    void should_keep_the_sensors_when_the_registry_cannot_be_read() {
        gatewayHolds(window(202, KATE, false, START));
        sensors.refresh().as(StepVerifier::create).verifyComplete();
        when(registryClient.getOpeningSensors()).thenReturn(Mono.error(new RegistryCallException("503")));
        now.set(START.plusSeconds(60));
        gatewayHolds(window(202, KATE, true, START.plusSeconds(30)));

        sensors.refresh().as(StepVerifier::create).verifyComplete();

        assertThat(sensors.stateOf(KATE_GATE).state()).isEqualTo(GarageState.OPEN);
    }

    //a gate of two sensors is open when one is, closed when both are, not known when one is not known
    @Test
    void should_judge_a_gate_of_two_sensors_by_both() {
        final GarageGate both = new GarageGate(150, "Both", "192.0.2.80", 0, List.of(69L, 70L));
        when(gateRegistry.garages()).thenReturn(List.of(new GateRegistry.GarageGateState(both, 1.0, true, null)));
        gatewayHolds(window(202, KATE, false, START.minusSeconds(60)));
        sensors.refresh().as(StepVerifier::create).verifyComplete();
        assertThat(sensors.stateOf(both).state()).isEqualTo(GarageState.UNKNOWN);

        gatewayHolds(window(202, KATE, false, START.minusSeconds(60)), window(206, KRIS, true, START.minusSeconds(30)));
        sensors.refresh().as(StepVerifier::create).verifyComplete();
        assertThat(sensors.stateOf(both).state()).isEqualTo(GarageState.OPEN);

        gatewayHolds(window(202, KATE, false, START.minusSeconds(60)), window(206, KRIS, false, START.minusSeconds(10)));
        sensors.refresh().as(StepVerifier::create).verifyComplete();
        assertThat(sensors.stateOf(both)).extracting(GarageSensors.GarageReading::state, GarageSensors.GarageReading::since)
            .containsExactly(GarageState.CLOSED, house(START.minusSeconds(10)));
    }
}
