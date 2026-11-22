package cloud.cholewa.gate.intercom;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;

//The entrance panel at the door woken by a ring (HAS-485, epic HAS-479): POST /home/amx/panel/doorbell of amx-service
//(>= 2.6.0) over k8s DNS, which tells the panel server of the Office master to wake the panel and show its doorbell
//page - no sound, ever. On every ring, not behind the window of the household message: a second press wakes a panel
//that went to sleep. It never fails nor stalls the device listener or the household message: bounded at
//RING_TIMEOUT, every failure a WARN - a 503 of amx-service (the Office master or its panel server away) included, it is
//no alert
@Slf4j
@Component
public class EntrancePanel {

    static final Duration RING_TIMEOUT = Duration.ofSeconds(5);
    static final String DOORBELL = "/home/amx/panel/doorbell";

    private final WebClient amxServiceWebClient;

    public EntrancePanel(@Qualifier("amxServiceWebClient") final WebClient amxServiceWebClient) {
        this.amxServiceWebClient = amxServiceWebClient;
    }

    public Mono<Void> ring(final LocalDateTime at) {
        return Mono.defer(() -> amxServiceWebClient.post()
                .uri(DOORBELL)
                .retrieve()
                //the code of amx-service stays in its own log; here the status says enough
                .onStatus(HttpStatusCode::isError, response -> response.releaseBody()
                    .then(Mono.just(new IllegalStateException("amx-service answered " + response.statusCode().value()))))
                .toBodilessEntity())
            .timeout(RING_TIMEOUT)
            .doOnSuccess(answered -> log.info("The entrance panel woken for the ring at {}", at))
            .onErrorResume(failure -> Mono.fromRunnable(() ->
                log.warn("The entrance panel not woken for the ring at {}: {}", at, failure.toString())))
            .then();
    }
}
