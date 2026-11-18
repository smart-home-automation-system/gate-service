package cloud.cholewa.gate.gate;

import cloud.cholewa.home.model.ShellyBinding;
import cloud.cholewa.home.model.SmartDevice;

import java.util.List;
import java.util.Optional;

//A garage gate of the registry (HAS-473): the kind gate with openingSensors (database-service >= 0.27.0) - the Shelly 1
//that pulses its drive and the BLU Door/Window sensors on the gate itself, by their registry ids. A gate without a sensor
//is the driveway gate (GateDevice); one without its id, its Shelly binding or its channel is not one the service can read
public record GarageGate(long id, String name, String address, int channel, List<Long> sensors) {

    public GarageGate {
        sensors = List.copyOf(sensors);
    }

    public static Optional<GarageGate> of(final SmartDevice device) {
        final ShellyBinding shelly = device.getShelly();
        final List<Long> sensors = device.getOpeningSensors() == null ? List.of() : device.getOpeningSensors();
        if (device.getId() == null || shelly == null || shelly.getChannel() == null || sensors.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new GarageGate(device.getId(), device.getName(), shelly.getAddress(), shelly.getChannel(),
            sensors));
    }

    //whether the registry holds this device as a garage gate: a gate with the sensors on it
    public static boolean isGarage(final SmartDevice device) {
        return device.getOpeningSensors() != null && !device.getOpeningSensors().isEmpty();
    }
}
