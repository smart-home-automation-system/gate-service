package cloud.cholewa.gate.intercom;

import cloud.cholewa.gate.config.IntercomProperties;
import cloud.cholewa.gate.database.IntercomEventEntity;
import cloud.cholewa.gate.gate.ScreenOpening;
import cloud.cholewa.gate.household.HouseholdNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//The topics and payloads recorded from the bridge on 2026-11-17 (deployment-tools/scripts/hikvision/devices.md)
class IntercomEventRouterTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    //16:13:40 of the house - the unlock below happened three seconds earlier
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-17T15:13:40Z"), HOUSE);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 17, 16, 13, 40);
    private static final String UNLOCK = """
        {"control_source": "494848494849494848484900", "number": "10010110001", "unlock_type": "HOUSEHOLDER", \
        "card_user_id": 0, "last_unlocked": "2026-11-17 15:13:37"}""";

    private final IntercomEventService service = mock(IntercomEventService.class);
    private final BridgeAvailability availability = new BridgeAvailability();
    private final ScreenOpening screenOpening = mock(ScreenOpening.class);
    private final HouseholdNotifier notifier = mock(HouseholdNotifier.class);
    private final EntrancePanel entrancePanel = mock(EntrancePanel.class);
    private final IntercomEventRouter router = new IntercomEventRouter(service, screenOpening, notifier, entrancePanel,
        availability,
        new IntercomProperties("gate", ZoneId.of("UTC")), JsonMapper.builder().build(), CLOCK);

    @BeforeEach
    void storeEverything() {
        when(service.store(any())).thenReturn(Mono.empty());
        when(screenOpening.onUnlock(any(), any(), any())).thenReturn(Mono.empty());
        when(notifier.doorbell(any())).thenReturn(Mono.empty());
        when(entrancePanel.ring(any())).thenReturn(Mono.empty());
    }

    //HAS-485: every ring wakes the entrance panel, the time it was heard - not behind the window of the household
    //message; a dismissed call does not
    @Test
    void should_wake_the_entrance_panel_on_every_ring() {
        route("hmd.sensor.gate.Call-state.state", "ringing");
        route("hmd.sensor.gate.Call-state.state", "dismissed");
        route("hmd.sensor.gate.Call-state.state", "ringing");

        verify(entrancePanel, times(2)).ring(NOW);
    }

    //HAS-452: a ring is told to the household at once, the time it was heard; a dismissed call is not
    @Test
    void should_tell_the_household_of_a_ring() {
        route("hmd.sensor.gate.Call-state.state", "ringing");
        route("hmd.sensor.gate.Call-state.state", "dismissed");

        verify(notifier).doorbell(NOW);
    }

    @Test
    void should_store_a_ring_with_the_button_its_attributes_named() {
        route("hmd.sensor.gate.Call-state.attributes", "{\"device_number\": \"10010100000\"}");
        route("hmd.sensor.gate.Call-state.state", "ringing");

        assertThat(stored()).isEqualTo(IntercomEventEntity.ring(NOW, "10010100000"));
    }

    //the button belongs to the ring it came with: a ring the attributes did not come before names none
    @Test
    void should_name_no_button_for_a_ring_without_its_attributes() {
        route("hmd.sensor.gate.Call-state.attributes", "{\"device_number\": \"10010100000\"}");
        route("hmd.sensor.gate.Call-state.state", "ringing");
        route("hmd.sensor.gate.Call-state.state", "ringing");

        final ArgumentCaptor<IntercomEventEntity> events = ArgumentCaptor.forClass(IntercomEventEntity.class);
        verify(service, times(2)).store(events.capture());
        assertThat(events.getAllValues()).extracting(IntercomEventEntity::deviceNumber)
            .containsExactly("10010100000", null);
    }

    @Test
    void should_store_a_dismissed_call() {
        route("hmd.sensor.gate.Call-state.state", "dismissed");

        assertThat(stored()).isEqualTo(IntercomEventEntity.dismissed(NOW));
    }

    //the bridge says idle after every end and 60 s after every ring
    @Test
    void should_store_nothing_for_idle() {
        route("hmd.sensor.gate.Call-state.state", "idle");

        verify(service, never()).store(any());
    }

    //last_unlocked is the wall time of the container of the bridge (UTC): 15:13:37 there is 16:13:37 of the house
    @Test
    void should_store_an_unlock_of_the_door_of_its_relay_at_the_time_of_the_station() {
        route("hmd.switch.gate.Door-1-relay.attributes", UNLOCK);

        assertThat(stored()).isEqualTo(IntercomEventEntity.unlock(LocalDateTime.of(2026, 11, 17, 16, 13, 37), NOW,
            "HOUSEHOLDER", "494848494849494848484900", "10010110001", 1));
    }

    //HAS-449: every unlock is handed to ScreenOpening, which decides whether it opens the gate
    @Test
    void should_hand_an_unlock_to_the_opening_of_the_screen() {
        route("hmd.switch.gate.Door-1-relay.attributes", UNLOCK);

        verify(screenOpening).onUnlock("HOUSEHOLDER", "10010110001", LocalDateTime.of(2026, 11, 17, 16, 13, 37));
    }

    //the gate opens whatever the write of the event comes to
    @Test
    void should_hand_an_unlock_to_the_opening_of_the_screen_when_its_write_fails() {
        when(service.store(any())).thenReturn(Mono.error(new IllegalStateException("database down")));

        router.route("hmd.switch.gate.Door-1-relay.attributes", UNLOCK.getBytes(StandardCharsets.UTF_8))
            .as(StepVerifier::create)
            .verifyError(IllegalStateException.class);

        verify(screenOpening).onUnlock("HOUSEHOLDER", "10010110001", LocalDateTime.of(2026, 11, 17, 16, 13, 37));
    }

    //one unlock is one pulse: the relay states and Door-unlocked say it again, the motion triggers nothing at all
    @ParameterizedTest
    @ValueSource(strings = {
        "hmd.switch.gate.Door-1-relay.state",
        "hmd.device_automation.gate.Door-unlocked.state",
        "hmd.device_automation.gate.motion_detection.state",
        "hmd.image.gate.Latest-Snapshot.state",
        "hmd.button.gate.Reboot.command",
        "hmd.sensor.gate.Call-state.command"
    })
    void should_store_nothing_for_the_rest_of_the_bridge(final String routingKey) {
        route(routingKey, UNLOCK);

        verify(service, never()).store(any());
    }

    //the queue gets every event of every device, and a station of another name is not this service's
    @ParameterizedTest
    @ValueSource(strings = {
        "shellypro4pm-08f9e0ea04c8.status.switch:2",
        "hmd.sensor.garden.Call-state.state",
        "homeassistant.sensor.gate.Call-state.config"
    })
    void should_store_nothing_for_another_device(final String routingKey) {
        route(routingKey, "ringing");

        verify(service, never()).store(any());
    }

    @Test
    void should_keep_the_availability_of_the_bridge() {
        route("hmd.sensor.gate.Call-state.availability", "online");

        assertThat(availability.latest()).contains(new BridgeAvailability.Report(true, NOW));
        verify(service, never()).store(any());
    }

    //a body that is not what its topic carries is logged and completed, and stores nothing
    @ParameterizedTest
    @ValueSource(strings = {"not json", "[1, 2]", "{\"last_unlocked\": \"yesterday\"}", "{}"})
    void should_complete_an_unlock_it_cannot_read(final String payload) {
        router.route("hmd.switch.gate.Door-1-relay.attributes", payload.getBytes(StandardCharsets.UTF_8))
            .as(StepVerifier::create)
            .verifyComplete();

        verify(service, never()).store(any());
    }

    @Test
    void should_complete_call_attributes_it_cannot_read_and_a_state_it_does_not_know() {
        route("hmd.sensor.gate.Call-state.attributes", "not json");
        route("hmd.sensor.gate.Call-state.state", "answered");

        verify(service, never()).store(any());
    }

    private void route(final String routingKey, final String payload) {
        router.route(routingKey, payload.getBytes(StandardCharsets.UTF_8))
            .as(StepVerifier::create)
            .verifyComplete();
    }

    private IntercomEventEntity stored() {
        final ArgumentCaptor<IntercomEventEntity> event = ArgumentCaptor.forClass(IntercomEventEntity.class);
        verify(service).store(event.capture());
        return event.getValue();
    }
}
