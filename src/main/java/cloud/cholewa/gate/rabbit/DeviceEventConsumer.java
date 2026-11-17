package cloud.cholewa.gate.rabbit;

import cloud.cholewa.gate.intercom.IntercomEventRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

//Every event of every device arrives here (HAS-448) - device.prod.gate is bound with "#", about 130 a minute: the
//status topics of the Shelly devices, the DeviceEvent of the Eaton devices and the topics of the Hikvision bridge.
//IntercomEventRouter picks those of the bridge. Logged at DEBUG only - at INFO the volume alone would drown the log
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceEventConsumer {

    private final IntercomEventRouter router;

    @RabbitListener(queues = "${rabbitmq.queue.name}")
    Mono<Void> consume(final Message message) {
        final String routingKey = message.getMessageProperties().getReceivedRoutingKey();

        return Mono.fromRunnable(() -> log.debug("Device event received with routing key: {}", routingKey))
            .then(Mono.defer(() -> router.route(routingKey, message.getBody())))
            //a Mono that errors is handed back by the broker at once - for a message that fails the same way a tight
            //loop - so whatever is left (the retries of the write are inside the chain) is logged and acknowledged
            .onErrorResume(throwable -> Mono.fromRunnable(() ->
                log.error("Error while consuming the device event {}", routingKey, throwable)))
            //the listener observation lives in a ThreadLocal the container does not hand to the reactor context; this
            //captures it at subscribe, where it is still present, so the traceId reaches the write
            .contextCapture();
    }
}
