package cloud.cholewa.gate.intercom;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

//HAS-485: the entrance panel woken by a ring through amx-service - a failure of any kind is a WARN and the ring goes on
class EntrancePanelTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 22, 19, 5, 30);

    private final ListAppender<ILoggingEvent> log = new ListAppender<>();
    private MockWebServer server;
    private EntrancePanel panel;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        //a connection of its own per test: no pooled connection to the server of a test before
        panel = new EntrancePanel(WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(HttpClient.newConnection()))
            .baseUrl("http://localhost:" + server.getPort())
            .build());
        log.start();
        ((Logger) LoggerFactory.getLogger(EntrancePanel.class)).addAppender(log);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(EntrancePanel.class)).detachAppender(log);
        server.close();
    }

    @Test
    void should_post_the_doorbell_of_amx_service() throws InterruptedException {
        server.enqueue(new MockResponse.Builder()
            .code(202)
            .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body("{\"sentAt\":\"2026-11-22T19:05:30\"}")
            .build());

        panel.ring(AT).as(StepVerifier::create).verifyComplete();

        final RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getUrl().encodedPath()).isEqualTo("/home/amx/panel/doorbell");
        assertThat(log.list).singleElement().satisfies(event -> assertThat(event.getLevel()).isEqualTo(Level.INFO));
    }

    //503: the Office master or its panel server away; 502 / 504 the panel server refusing or silent - none an alert
    @ParameterizedTest
    @ValueSource(ints = {502, 503, 504, 500})
    void should_complete_with_a_warn_when_amx_service_refuses(final int status) {
        server.enqueue(new MockResponse.Builder()
            .code(status)
            .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body("{\"errors\":[{\"code\":\"PANEL_SERVER_UNAVAILABLE\"}]}")
            .build());

        panel.ring(AT).as(StepVerifier::create).verifyComplete();

        assertThat(log.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("amx-service answered " + status);
        });
    }

    //amx-service down: the ring still completes, at once
    @Test
    void should_complete_with_a_warn_when_amx_service_is_down() {
        server.close();

        panel.ring(AT).as(StepVerifier::create).expectComplete().verify(Duration.ofSeconds(10));

        assertThat(log.list).singleElement().satisfies(event -> assertThat(event.getLevel()).isEqualTo(Level.WARN));
    }

    //a silent amx-service holds the ring at most RING_TIMEOUT
    @Test
    void should_give_up_after_the_timeout() {
        server.enqueue(new MockResponse.Builder().code(202).headersDelay(30, TimeUnit.SECONDS).build());

        panel.ring(AT).as(StepVerifier::create)
            .expectComplete()
            .verify(EntrancePanel.RING_TIMEOUT.plusSeconds(5));

        assertThat(log.list).singleElement().satisfies(event -> assertThat(event.getLevel()).isEqualTo(Level.WARN));
    }
}
