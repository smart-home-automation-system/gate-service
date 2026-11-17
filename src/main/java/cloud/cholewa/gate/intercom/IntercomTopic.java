package cloud.cholewa.gate.intercom;

import java.util.Optional;

//A topic of the Hikvision bridge as it reaches a queue of /device: hmd/<component>/<device>/<entity>/<suffix> with
//every "/" turned into "." by the MQTT plugin - hmd.sensor.gate.Call-state.state. The names of the entities hold
//hyphens, never dots (deployment-tools/scripts/hikvision/devices.md), so a routing key of the bridge has exactly
//five parts; anything else is not the bridge's
public record IntercomTopic(String component, String device, String entity, String suffix) {

    private static final String PREFIX = "hmd";
    private static final int PARTS = 5;

    public static Optional<IntercomTopic> of(final String routingKey) {
        if (routingKey == null) {
            return Optional.empty();
        }
        final String[] parts = routingKey.split("\\.", -1);
        if (parts.length != PARTS || !PREFIX.equals(parts[0])) {
            return Optional.empty();
        }
        for (final String part : parts) {
            if (part.isEmpty()) {
                return Optional.empty();
            }
        }
        return Optional.of(new IntercomTopic(parts[1], parts[2], parts[3], parts[4]));
    }
}
