package cloud.cholewa.gate.intercom;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class BridgeAvailabilityTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 17, 16, 0);

    private final BridgeAvailability availability = new BridgeAvailability();

    @Test
    void should_know_nothing_until_the_bridge_says_something() {
        assertThat(availability.latest()).isEmpty();
    }

    //the last will of the bridge says offline for every entity: the newest report is the state of the bridge
    @Test
    void should_answer_the_newest_report_of_any_entity() {
        availability.report("Call-state", "online", AT);
        availability.report("Door-1-relay", "offline", AT.plusMinutes(5));

        assertThat(availability.latest()).contains(new BridgeAvailability.Report(false, AT.plusMinutes(5)));

        availability.report("Call-state", "online", AT.plusMinutes(6));

        assertThat(availability.latest()).contains(new BridgeAvailability.Report(true, AT.plusMinutes(6)));
    }

    @Test
    void should_ignore_a_word_the_bridge_does_not_use() {
        availability.report("Call-state", "unknown", AT);

        assertThat(availability.latest()).isEmpty();
    }
}
