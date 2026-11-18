package cloud.cholewa.gate.garage;

import cloud.cholewa.shelly.codec.ShellyMqttDecoder;
import cloud.cholewa.shelly.model.BthomeSensorStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//HAS-473: of the 130 events a minute only the status topic of a component GarageSensors knows reaches it
class GarageEventRouterTest {

    private static final String GATEWAY = "shellypro3em-0123456789ab";

    private final GarageSensors sensors = mock(GarageSensors.class);
    private final GarageEventRouter router = new GarageEventRouter(sensors, new ShellyMqttDecoder(JsonMapper.builder().build()));

    @Test
    void should_hand_a_known_sensor_its_status() {
        when(sensors.knows(GATEWAY, 202)).thenReturn(true);
        when(sensors.observe(anyString(), anyInt(), any())).thenReturn(Mono.empty());
        final byte[] body = "{\"id\":202,\"value\":true,\"last_updated_ts\":1795031097}".getBytes(StandardCharsets.UTF_8);

        router.route(GATEWAY + ".status.bthomesensor:202", body).as(StepVerifier::create).verifyComplete();

        final ArgumentCaptor<BthomeSensorStatus> status = ArgumentCaptor.forClass(BthomeSensorStatus.class);
        verify(sensors).observe(eq(GATEWAY), eq(202), status.capture());
        assertThat(status.getValue().getValue()).isEqualTo(true);
        assertThat(status.getValue().getLastUpdatedTs()).isEqualTo(1795031097L);
    }

    @Test
    void should_drop_everything_else_before_reading_a_body() {
        when(sensors.knows(GATEWAY, 210)).thenReturn(false);
        final byte[] notJson = "not json".getBytes(StandardCharsets.UTF_8);

        router.route(GATEWAY + ".status.bthomesensor:210", notJson).as(StepVerifier::create).verifyComplete();
        router.route(GATEWAY + ".status.switch:0", notJson).as(StepVerifier::create).verifyComplete();
        router.route("hmd.sensor.gate.Call-state.state", notJson).as(StepVerifier::create).verifyComplete();

        verify(sensors, never()).observe(anyString(), anyInt(), any());
    }
}
