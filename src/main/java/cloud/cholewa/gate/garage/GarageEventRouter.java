package cloud.cholewa.gate.garage;

import cloud.cholewa.shelly.codec.ShellyEvent;
import cloud.cholewa.shelly.codec.ShellyMqttDecoder;
import cloud.cholewa.shelly.codec.ShellyTopic;
import cloud.cholewa.shelly.model.BthomeSensorStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

//The status topics of the BTHome gateways that carry a sensor of a garage gate (HAS-473):
//<gatewayId>.status.bthomesensor:<id> of a component GarageSensors knows - the state of that sensor. Everything else of
//the queue is dropped without a word, before a body is read. A body that is not what its topic carries is the decoder's
//exception, handled by the listener chain
@Component
@RequiredArgsConstructor
public class GarageEventRouter {

    private static final String BTHOME_SENSOR = "bthomesensor";

    private final GarageSensors garageSensors;
    private final ShellyMqttDecoder shellyMqttDecoder;

    public Mono<Void> route(final String routingKey, final byte[] body) {
        return ShellyTopic.parse(routingKey)
            .filter(topic -> topic.kind() == ShellyTopic.Kind.STATUS && BTHOME_SENSOR.equals(topic.component())
                && garageSensors.knows(topic.deviceId(), topic.index()))
            .map(topic -> sensor(topic, body))
            .orElseGet(Mono::empty);
    }

    private Mono<Void> sensor(final ShellyTopic topic, final byte[] body) {
        return Mono.fromCallable(() -> shellyMqttDecoder.decode(topic, body))
            .flatMap(Mono::justOrEmpty)
            .cast(ShellyEvent.Status.class)
            .flatMap(status -> Mono.justOrEmpty(status.as(BthomeSensorStatus.class)))
            .flatMap(status -> garageSensors.observe(topic.deviceId(), topic.index(), status))
            .then();
    }
}
