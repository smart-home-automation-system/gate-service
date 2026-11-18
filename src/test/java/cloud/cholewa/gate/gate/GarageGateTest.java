package cloud.cholewa.gate.gate;

import cloud.cholewa.home.model.DeviceKind;
import cloud.cholewa.home.model.ShellyBinding;
import cloud.cholewa.home.model.SmartDevice;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

//HAS-473: a garage gate is a gate with the sensors on it, which the service can reach
class GarageGateTest {

    private static SmartDevice.SmartDeviceBuilder<?, ?> gate(final Integer channel, final List<Long> sensors) {
        return SmartDevice.builder().id(148L).name("Kate").kind(DeviceKind.GATE).enabled(true)
            .shelly(ShellyBinding.builder().address("192.0.2.79").generation(1).channel(channel).build())
            .openingSensors(sensors);
    }

    @Test
    void should_take_a_gate_with_its_sensors() {
        assertThat(GarageGate.of(gate(0, List.of(69L)).build()))
            .contains(new GarageGate(148, "Kate", "192.0.2.79", 0, List.of(69L)));
        assertThat(GarageGate.isGarage(gate(0, List.of(69L)).build())).isTrue();
    }

    //the driveway gate has no sensor; a gate without its channel, its binding or its id cannot be read
    @Test
    void should_leave_out_a_gate_without_sensors_or_what_reaches_it() {
        assertThat(GarageGate.of(gate(0, List.of()).build())).isEmpty();
        assertThat(GarageGate.of(gate(0, null).build())).isEmpty();
        assertThat(GarageGate.isGarage(gate(0, null).build())).isFalse();
        assertThat(GarageGate.of(gate(null, List.of(69L)).build())).isEmpty();
        assertThat(GarageGate.of(gate(0, List.of(69L)).shelly(null).build())).isEmpty();
        assertThat(GarageGate.of(gate(0, List.of(69L)).id(null).build())).isEmpty();
    }
}
