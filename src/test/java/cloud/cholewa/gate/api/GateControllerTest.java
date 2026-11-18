package cloud.cholewa.gate.api;

import cloud.cholewa.gate.api.model.GateReply;
import cloud.cholewa.gate.config.ExceptionHandlerConfig;
import cloud.cholewa.gate.error.GateException;
import cloud.cholewa.gate.gate.GateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@WebFluxTest(GateController.class)
@Import(ExceptionHandlerConfig.class)
@ActiveProfiles("test")
class GateControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private GateService gateService;

    @Test
    void should_open_the_gate_from_the_dashboard() {
        when(gateService.openFromDashboard("Anna")).thenReturn(Mono.empty());

        webTestClient.post().uri("/open?source=dashboard&member=Anna").exchange().expectStatus().isNoContent();

        verify(gateService).openFromDashboard("Anna");
    }

    //the screen opens the gate by the unlock of the station, never over HTTP
    @Test
    void should_refuse_another_source_or_no_member() {
        webTestClient.post().uri("/open?source=screen&member=Anna").exchange().expectStatus().isBadRequest();
        webTestClient.post().uri("/open?source=dashboard&member=").exchange().expectStatus().isBadRequest();
        webTestClient.post().uri("/open?source=dashboard").exchange().expectStatus().isBadRequest();

        verify(gateService, never()).openFromDashboard(any());
    }

    static Stream<Arguments> refusals() {
        return Stream.of(
            Arguments.of(new GateException.UnknownMember("Borys"), HttpStatus.BAD_REQUEST, "UNKNOWN_MEMBER"),
            Arguments.of(new GateException.OpenTooSoon(15), HttpStatus.CONFLICT, "OPEN_TOO_SOON"),
            Arguments.of(new GateException.DeviceFailed("timeout"), HttpStatus.BAD_GATEWAY, "DEVICE_FAILED"),
            Arguments.of(new GateException.GateUnavailable("no gate"), HttpStatus.SERVICE_UNAVAILABLE, "GATE_UNAVAILABLE"),
            Arguments.of(new GateException.RegistryUnavailable(), HttpStatus.BAD_GATEWAY, "REGISTRY_UNAVAILABLE"));
    }

    @ParameterizedTest
    @MethodSource("refusals")
    void should_answer_a_refusal_with_its_status_and_code(final GateException refusal, final HttpStatus status,
                                                          final String code) {
        when(gateService.openFromDashboard("Anna")).thenReturn(Mono.error(refusal));

        webTestClient.post().uri("/open?source=dashboard&member=Anna").exchange()
            .expectStatus().isEqualTo(status)
            .expectBody().jsonPath("$.errors[0].code").isEqualTo(code);
    }

    @Test
    void should_read_the_gate_leaving_out_what_is_not_known() {
        when(gateService.read()).thenReturn(Mono.just(new GateReply(null, new GateReply.Bridge(true,
            LocalDateTime.of(2026, 11, 18, 9, 0)), new GateReply.Ring(LocalDateTime.of(2026, 11, 18, 8, 59), null), null)));

        webTestClient.get().uri("").exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.gate").doesNotExist()
            .jsonPath("$.bridge.online").isEqualTo(true)
            .jsonPath("$.lastRing.at").isEqualTo("2026-11-18T08:59:00")
            .jsonPath("$.lastRing.button").doesNotExist()
            .jsonPath("$.lastOpening").doesNotExist();
    }
}
