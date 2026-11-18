package cloud.cholewa.gate.api;

import cloud.cholewa.gate.api.model.GarageGateReply;
import cloud.cholewa.gate.config.ExceptionHandlerConfig;
import cloud.cholewa.gate.error.GateException;
import cloud.cholewa.gate.garage.GarageGateService;
import cloud.cholewa.gate.garage.GarageState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

import static org.mockito.Mockito.when;

//HAS-473: the read of the garage gates - the fields are wire contract for the dashboard (HAS-454)
@WebFluxTest(GarageController.class)
@Import(ExceptionHandlerConfig.class)
@ActiveProfiles("test")
class GarageControllerTest {

    private static final LocalDateTime SINCE = LocalDateTime.of(2026, 11, 18, 19, 44, 57);
    private static final LocalDateTime CONFIRMED = LocalDateTime.of(2026, 11, 18, 19, 50);

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private GarageGateService garageGateService;

    @Test
    void should_answer_every_garage_gate_and_leave_out_what_is_not_known() {
        when(garageGateService.all()).thenReturn(Flux.just(
            new GarageGateReply(148, "Kate", GarageState.OPEN, SINCE, CONFIRMED, true, true),
            new GarageGateReply(149, "Kris", GarageState.UNKNOWN, null, null, false, false)));

        webTestClient.get().uri("/garage").exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.length()").isEqualTo(2)
            .jsonPath("$[0].id").isEqualTo(148)
            .jsonPath("$[0].name").isEqualTo("Kate")
            .jsonPath("$[0].state").isEqualTo("open")
            .jsonPath("$[0].since").isEqualTo("2026-11-18T19:44:57")
            .jsonPath("$[0].confirmedAt").isEqualTo("2026-11-18T19:50:00")
            .jsonPath("$[0].reachable").isEqualTo(true)
            .jsonPath("$[0].safe").isEqualTo(true)
            .jsonPath("$[1].state").isEqualTo("unknown")
            .jsonPath("$[1].since").doesNotExist()
            .jsonPath("$[1].confirmedAt").doesNotExist();
    }

    @Test
    void should_answer_one_garage_gate_and_404_for_an_id_that_is_none() {
        when(garageGateService.one(148)).thenReturn(Mono.just(
            new GarageGateReply(148, "Kate", GarageState.CLOSED, SINCE, CONFIRMED, true, true)));
        when(garageGateService.one(147)).thenReturn(Mono.error(new GateException.GateNotFound(147)));

        webTestClient.get().uri("/garage/148").exchange()
            .expectStatus().isOk()
            .expectBody().jsonPath("$.state").isEqualTo("closed");
        webTestClient.get().uri("/garage/147").exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.errors[0].code").isEqualTo("NOT_FOUND_GATE")
            .jsonPath("$.errors[0].details").isEqualTo("No garage gate of the id 147");
    }
}
