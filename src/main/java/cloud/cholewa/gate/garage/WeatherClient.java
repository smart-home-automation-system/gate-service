package cloud.cholewa.gate.garage;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

import java.util.Optional;

//The temperature outside, from GET /home/weather/current of weather-service over k8s DNS (HAS-475): the metric
//temperature of the station - {value (C), measuredAt, stale}. The shape of the illuminance client of energy-service. An
//answer without the metric, or with a value that is not a number, is no temperature (empty); an error of weather-service
//is a failed call, never a warm day
@Component
public class WeatherClient {

    private final WebClient weatherServiceWebClient;

    public WeatherClient(@Qualifier("weatherServiceWebClient") final WebClient weatherServiceWebClient) {
        this.weatherServiceWebClient = weatherServiceWebClient;
    }

    //degrees C and whether weather-service holds the reading for stale
    public record Temperature(double degrees, boolean stale) {
    }

    public Mono<Temperature> outside() {
        return weatherServiceWebClient.get()
            .uri("/home/weather/current")
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .onStatus(HttpStatusCode::isError, response ->
                Mono.just(new IllegalStateException("weather-service answered " + response.statusCode().value())))
            .bodyToMono(JsonNode.class)
            .switchIfEmpty(Mono.error(() -> new IllegalStateException("weather-service answered without a body")))
            .flatMap(current -> Mono.justOrEmpty(of(current.path("temperature"))));
    }

    static Optional<Temperature> of(final JsonNode metric) {
        return Optional.of(metric)
            .filter(node -> node.path("value").isNumber())
            .map(node -> new Temperature(node.path("value").asDouble(), node.path("stale").asBoolean(true)));
    }
}
