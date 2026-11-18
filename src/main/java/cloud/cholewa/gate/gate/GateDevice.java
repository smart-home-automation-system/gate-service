package cloud.cholewa.gate.gate;

import cloud.cholewa.home.model.ShellyBinding;
import cloud.cholewa.home.model.SmartDevice;

import java.util.Optional;

//The Shelly that pulses the drive of the gate, as the registry holds it (the kind gate, database-service >= 0.26.0): its
//id, its name (the subject of the household message of an opening, HAS-452), its address and the channel of its relay - 0, the one relay of the Shelly 1 (checkGate of the registry). A device
//without its id, its Shelly binding or its channel is not one the service can drive
public record GateDevice(long id, String name, String address, int channel) {

    public static Optional<GateDevice> of(final SmartDevice device) {
        final ShellyBinding shelly = device.getShelly();
        //the address is required in the SDK (@Nonnull); the id and the channel may be left out of an answer
        if (device.getId() == null || shelly == null || shelly.getChannel() == null) {
            return Optional.empty();
        }
        return Optional.of(new GateDevice(device.getId(), device.getName(), shelly.getAddress(), shelly.getChannel()));
    }
}
