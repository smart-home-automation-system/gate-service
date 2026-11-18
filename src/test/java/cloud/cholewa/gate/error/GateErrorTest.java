package cloud.cholewa.gate.error;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

//The names of the causes are the codes of the answers - wire contract for the dashboard (HAS-454): renaming one is a
//breaking change, made together with the dashboard
class GateErrorTest {

    @Test
    void should_keep_the_names_of_the_codes() {
        assertThat(Arrays.stream(GateError.values()).map(Enum::name)).containsExactly(
            "UNKNOWN_MEMBER", "OPEN_TOO_SOON", "DEVICE_FAILED", "GATE_UNAVAILABLE", "REGISTRY_UNAVAILABLE", "INVALID_RANGE");
    }
}
