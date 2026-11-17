package cloud.cholewa.gate.intercom;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class IntercomTopicTest {

    @Test
    void should_read_the_five_parts_of_a_topic_of_the_bridge() {
        assertThat(IntercomTopic.of("hmd.sensor.gate.Call-state.state"))
            .contains(new IntercomTopic("sensor", "gate", "Call-state", "state"));
        assertThat(IntercomTopic.of("hmd.switch.gate.Door-1-relay.attributes"))
            .contains(new IntercomTopic("switch", "gate", "Door-1-relay", "attributes"));
    }

    //the queue gets every event of every device: the status topics of a Shelly, the DeviceEvent of an Eaton device, the
    //discovery of Home Assistant, a topic of the bridge with a part missing or one more
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
        "shellypro4pm-08f9e0ea04c8.status.switch:2",
        "eaton.blinds.45",
        "homeassistant.sensor.gate.Call-state.config",
        "hmd.sensor.gate.Call-state",
        "hmd.sensor.gate.Call-state.state.extra",
        "hmd..gate.Call-state.state",
        "hmd.sensor.gate.Call-state.",
        ""
    })
    void should_take_nothing_else_for_a_topic_of_the_bridge(final String routingKey) {
        assertThat(IntercomTopic.of(routingKey)).isEmpty();
    }
}
