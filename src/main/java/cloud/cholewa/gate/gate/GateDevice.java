package cloud.cholewa.gate.gate;

import cloud.cholewa.home.model.ShellyBinding;
import cloud.cholewa.home.model.SmartDevice;

import java.util.Optional;

//The Shelly that pulses the drive of the gate, as the registry holds it (the kind gate, database-service >= 0.26.0): its
//id, its address and the channel of its relay - 0, the one relay of the Shelly 1 (checkGate of the registry). A device
//without its Shelly binding, address or channel is not one the service can drive
public record GateDevice(long id, String address, int channel) {

    public static Optional<GateDevice> of(final SmartDevice device) {
        final ShellyBinding shelly = device.getShelly();
        if (device.getId() == null || shelly == null || shelly.getAddress() == null || shelly.getChannel() == null) {
            return Optional.empty();
        }
        return Optional.of(new GateDevice(device.getId(), shelly.getAddress(), shelly.getChannel()));
    }
}
