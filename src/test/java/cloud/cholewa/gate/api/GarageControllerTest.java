package cloud.cholewa.gate.api;

import cloud.cholewa.gate.api.model.GarageAlertReply;
import cloud.cholewa.gate.api.model.GarageGateReply;
import cloud.cholewa.gate.config.ExceptionHandlerConfig;
import cloud.cholewa.gate.error.GateException;
import cloud.cholewa.gate.garage.GarageAlertService;
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

import static org.mockito.Mockito.verify;
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

    @MockitoBean
    private GarageAlertService garageAlertService;

    //HAS-454: the open alarms and warnings - their fields are wire contract for the dashboard; a gate the registry no
    //longer knows has no name
    @Test
    void should_answer_the_open_alarms_of_the_garage_gates() {
        when(garageAlertService.open()).thenReturn(Flux.just(
            new GarageAlertReply(148, "Kate", "cold", "alarm", SINCE, SINCE.plusMinutes(15), 2),
            new GarageAlertReply(149, null, "long", "warning", SINCE, SINCE.plusMinutes(30), 0)));

        webTestClient.get().uri("/garage/alerts").exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.length()").isEqualTo(2)
            .jsonPath("$[0].gate").isEqualTo(148)
            .jsonPath("$[0].name").isEqualTo("Kate")
            .jsonPath("$[0].kind").isEqualTo("cold")
            .jsonPath("$[0].level").isEqualTo("alarm")
            .jsonPath("$[0].openSince").isEqualTo("2026-11-18T19:44:57")
            .jsonPath("$[0].raisedAt").isEqualTo("2026-11-18T19:59:57")
            .jsonPath("$[0].reminders").isEqualTo(2)
            .jsonPath("$[1].name").doesNotExist()
            .jsonPath("$[1].level").isEqualTo("warning");
    }

    @Test
    void should_answer_every_garage_gate_and_leave_out_what_is_not_known() {
        when(garageGateService.all()).thenReturn(Flux.just(
            new GarageGateReply(148, "Kate", GarageState.OPEN, SINCE, CONFIRMED, true, true, false),
            new GarageGateReply(149, "Kris", GarageState.UNKNOWN, null, null, false, false, null)));

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
            .jsonPath("$[0].lastCommandConfirmed").isEqualTo(false)
            .jsonPath("$[1].lastCommandConfirmed").doesNotExist()
            .jsonPath("$[1].state").isEqualTo("unknown")
            .jsonPath("$[1].since").doesNotExist()
            .jsonPath("$[1].confirmedAt").doesNotExist();
    }

    //HAS-474: one pulse from the dashboard, by a member; another source or no member is refused before anything is sent
    @Test
    void should_pulse_a_garage_gate_for_a_member_of_the_dashboard() {
        when(garageGateService.pulse(148, "Anna")).thenReturn(Mono.empty());

        webTestClient.post().uri("/garage/148/pulse?source=dashboard&member=Anna").exchange()
            .expectStatus().isNoContent();
        webTestClient.post().uri("/garage/148/pulse?source=screen&member=Anna").exchange()
            .expectStatus().isBadRequest();
        webTestClient.post().uri("/garage/148/pulse?source=dashboard").exchange()
            .expectStatus().isBadRequest();

        verify(garageGateService).pulse(148, "Anna");
    }

    @Test
    void should_answer_the_codes_of_a_refused_pulse() {
        when(garageGateService.pulse(147, "Anna")).thenReturn(Mono.error(new GateException.GateNotFound(147)));
        when(garageGateService.pulse(148, "Anna")).thenReturn(Mono.error(new GateException.DeviceFailed("timeout")));
        when(garageGateService.pulse(149, "Anna")).thenReturn(Mono.error(new GateException.GateUnavailable("auto_off 0")));

        webTestClient.post().uri("/garage/147/pulse?source=dashboard&member=Anna").exchange()
            .expectStatus().isNotFound();
        webTestClient.post().uri("/garage/148/pulse?source=dashboard&member=Anna").exchange()
            .expectStatus().isEqualTo(502)
            .expectBody().jsonPath("$.errors[0].code").isEqualTo("DEVICE_FAILED");
        webTestClient.post().uri("/garage/149/pulse?source=dashboard&member=Anna").exchange()
            .expectStatus().isEqualTo(503);
    }

    @Test
    void should_answer_one_garage_gate_and_404_for_an_id_that_is_none() {
        when(garageGateService.one(148)).thenReturn(Mono.just(
            new GarageGateReply(148, "Kate", GarageState.CLOSED, SINCE, CONFIRMED, true, true, null)));
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
