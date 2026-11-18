package cloud.cholewa.gate.gate;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

//The settings of the relay of a Shelly 1 Gen1, as the Shelly of the gate answered them on 2026-11-17
class GateSettingsClientTest {

    private MockWebServer server;
    private GateSettingsClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new GateSettingsClient(WebClient.builder().build());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private String address() {
        return "localhost:" + server.getPort();
    }

    private static MockResponse json(final String body) {
        return new MockResponse.Builder().code(200)
            .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).body(body).build();
    }

    @Test
    void should_read_the_auto_off_of_the_relay_of_its_channel() throws InterruptedException {
        server.enqueue(json("""
            {"name":null,"appliance_type":"General","ison":false,"has_timer":false,"default_state":"off",
             "btn_type":"toggle","btn_reverse":1,"auto_on":0.00,"auto_off":1.00,"power":0.00,"schedule":false}
            """));

        client.autoOff(address(), 0).as(StepVerifier::create).expectNext(1.0).verifyComplete();

        final RecordedRequest request = server.takeRequest();
        assertThat(request.getUrl().encodedPath()).isEqualTo("/settings/relay/0");
    }

    //an answer without auto_off is a failed read, never 0 - nor a relay safe to command
    @Test
    void should_fail_on_an_answer_without_auto_off() {
        server.enqueue(json("{\"ison\":false}"));

        client.autoOff(address(), 0).as(StepVerifier::create).verifyError(IllegalStateException.class);
    }

    @Test
    void should_fail_when_the_device_fails() {
        server.enqueue(new MockResponse.Builder().code(500).build());

        client.autoOff(address(), 0).as(StepVerifier::create).verifyError();
    }
}
