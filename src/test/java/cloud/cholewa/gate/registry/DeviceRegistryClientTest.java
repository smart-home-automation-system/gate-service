package cloud.cholewa.gate.registry;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.cholewa.home.model.SmartDevice;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceRegistryClientTest {

    private final ListAppender<ILoggingEvent> clientLog = new ListAppender<>();
    private MockWebServer server;
    private DeviceRegistryClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new DeviceRegistryClient(WebClient.builder().baseUrl("http://localhost:" + server.getPort()).build());
        clientLog.start();
        ((Logger) LoggerFactory.getLogger(DeviceRegistryClient.class)).addAppender(clientLog);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(DeviceRegistryClient.class)).detachAppender(clientLog);
        server.close();
    }

    @Test
    void should_read_the_switched_on_gates() throws InterruptedException {
        server.enqueue(json("""
            [{"id":147,"name":"Gate","room":"garden","kind":"gate","enabled":true,"vendor":"shelly",
              "shelly":{"address":"192.0.2.85","generation":1,"channel":0}}]
            """));

        client.getGates().as(StepVerifier::create)
            .assertNext(devices -> assertThat(devices).singleElement().satisfies(device -> {
                assertThat(device.getId()).isEqualTo(147L);
                assertThat(device.getShelly().getAddress()).isEqualTo("192.0.2.85");
            }))
            .verifyComplete();

        final RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("GET");
        assertThat(request.getUrl().encodedPath()).isEqualTo("/home/device/registry");
        assertThat(request.getUrl().queryParameter("kind")).isEqualTo("gate");
        assertThat(request.getUrl().queryParameter("enabled")).isEqualTo("true");
    }

    @Test
    void should_read_an_empty_list_as_no_gate() {
        server.enqueue(json("[]"));

        client.getGates().as(StepVerifier::create)
            .assertNext(devices -> assertThat(devices).extracting(SmartDevice::getId).isEmpty())
            .verifyComplete();
    }

    //an error is never an empty list - that would forget the gate; what the downstream said is a
    //WARN, never passed on
    @Test
    void should_fail_when_database_service_fails() {
        server.enqueue(new MockResponse.Builder().code(500)
            .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body("{\"errors\":[{\"message\":\"Internal error\"}]}").build());

        client.getGates().as(StepVerifier::create)
            .expectErrorSatisfies(e -> assertThat(e)
                .isInstanceOf(RegistryCallException.class)
                .hasMessage("database-service answered 500"))
            .verify();

        assertThat(clientLog.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("500", "gates");
        });
    }

    @Test
    void should_fail_on_an_answer_without_a_body() {
        server.enqueue(new MockResponse.Builder().code(200).build());

        client.getGates().as(StepVerifier::create)
            .expectErrorSatisfies(e -> assertThat(e)
                .isInstanceOf(RegistryCallException.class)
                .hasMessage("database-service answered without a list"))
            .verify();
    }

    @Test
    void should_fail_on_an_answer_that_is_not_a_list() {
        server.enqueue(json("{}"));

        client.getGates().as(StepVerifier::create).expectError().verify();
    }

    private static MockResponse json(final String body) {
        return new MockResponse.Builder()
            .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body(body)
            .build();
    }
}
