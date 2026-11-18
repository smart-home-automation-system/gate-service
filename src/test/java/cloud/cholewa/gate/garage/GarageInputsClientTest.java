package cloud.cholewa.gate.garage;

import cloud.cholewa.gate.garage.PresenceClient.ResidentPresence;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

//HAS-475: the temperature outside (weather-service) and who is at home (presence-service), as the alarms read them - a
//failed call is an error, never a warm day or an empty house
class GarageInputsClientTest {

    private MockWebServer server;
    private WeatherClient weatherClient;
    private PresenceClient presenceClient;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        final WebClient webClient = WebClient.builder().baseUrl("http://localhost:" + server.getPort()).build();
        weatherClient = new WeatherClient(webClient);
        presenceClient = new PresenceClient(webClient);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private static MockResponse json(final int status, final String body) {
        return new MockResponse.Builder()
            .code(status)
            .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body(body)
            .build();
    }

    @Test
    void should_read_the_temperature_outside_and_whether_it_is_stale() throws InterruptedException {
        server.enqueue(json(200, """
            {"temperature":{"value":7.5,"measuredAt":"2026-11-18T19:14:00","stale":false},
             "humidity":{"value":90,"stale":false}}
            """));

        weatherClient.outside().as(StepVerifier::create)
            .expectNext(new WeatherClient.Temperature(7.5, false))
            .verifyComplete();
        assertThat(server.takeRequest().getUrl().encodedPath()).isEqualTo("/home/weather/current");
    }

    //a metric left out, or one that is no number, is no temperature; an error of weather-service is a failed call
    @Test
    void should_answer_no_temperature_without_the_metric_and_fail_on_an_error() {
        server.enqueue(json(200, "{\"humidity\":{\"value\":90}}"));
        server.enqueue(json(200, "{\"temperature\":{\"value\":\"cold\"}}"));
        server.enqueue(json(503, "{}"));

        weatherClient.outside().as(StepVerifier::create).verifyComplete();
        weatherClient.outside().as(StepVerifier::create).verifyComplete();
        weatherClient.outside().as(StepVerifier::create).expectErrorMessage("weather-service answered 503").verify();
    }

    @Test
    void should_read_the_residents_and_fail_on_an_error() {
        server.enqueue(json(200, """
            [{"name":"Anna","present":false,"since":"2026-11-18T18:00:00","lastCheckedAt":"2026-11-18T19:14:00"}]
            """));
        server.enqueue(json(503, "{\"errors\":[{\"message\":\"down\"}]}"));

        presenceClient.residents().as(StepVerifier::create)
            .expectNext(List.of(new ResidentPresence("Anna", false, LocalDateTime.of(2026, 11, 18, 18, 0),
                LocalDateTime.of(2026, 11, 18, 19, 14))))
            .verifyComplete();
        presenceClient.residents().as(StepVerifier::create).expectErrorMessage("presence-service answered 503").verify();
    }
}
