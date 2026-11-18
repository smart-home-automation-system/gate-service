package cloud.cholewa.gate.gate;

import cloud.cholewa.home.model.SmartDevice;

import java.util.Optional;

//The door station at the gate as the registry holds it (the kind intercom, database-service >= 0.26.0): its id and its
//name - the subject of the household message of a ring (HAS-452). A device without its id is none
public record IntercomDevice(long id, String name) {

    public static Optional<IntercomDevice> of(final SmartDevice device) {
        return Optional.ofNullable(device.getId()).map(id -> new IntercomDevice(id, device.getName()));
    }
}
