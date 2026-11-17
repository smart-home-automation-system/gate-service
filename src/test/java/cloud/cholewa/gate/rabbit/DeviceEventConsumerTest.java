package cloud.cholewa.gate.rabbit;

import cloud.cholewa.gate.intercom.IntercomEventRouter;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceEventConsumerTest {

    private static final String KEY = "hmd.sensor.gate.Call-state.state";

    private final IntercomEventRouter router = mock(IntercomEventRouter.class);
    private final DeviceEventConsumer consumer = new DeviceEventConsumer(router);

    @Test
    void should_hand_the_event_to_the_router_by_its_routing_key() {
        when(router.route(eq(KEY), any())).thenReturn(Mono.empty());

        consumer.consume(message()).as(StepVerifier::create).verifyComplete();

        verify(router).route(eq(KEY), any());
    }

    //an error out of the listener is handed back by the broker at once - a tight loop; the event is logged and
    //acknowledged
    @Test
    void should_complete_an_event_the_router_failed_on() {
        when(router.route(eq(KEY), any())).thenReturn(Mono.error(new IllegalStateException("unexpected")));

        consumer.consume(message()).as(StepVerifier::create).verifyComplete();
    }

    private static Message message() {
        final MessageProperties properties = new MessageProperties();
        properties.setReceivedRoutingKey(KEY);
        return new Message("ringing".getBytes(StandardCharsets.UTF_8), properties);
    }
}
