package cloud.cholewa.gate.api;

import cloud.cholewa.gate.api.model.GateEvent;
import cloud.cholewa.gate.api.model.GateReply;
import cloud.cholewa.gate.config.ExceptionHandlerConfig;
import cloud.cholewa.gate.error.GateException;
import cloud.cholewa.gate.events.GateEventService;
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
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Flux;
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

    @MockitoBean
    private GateEventService gateEventService;

    @Test
    void should_open_the_gate_from_the_dashboard() {
        when(gateService.open("dashboard", "Anna")).thenReturn(Mono.empty());

        webTestClient.post().uri("/open?source=dashboard&member=Anna").exchange().expectStatus().isNoContent();

        verify(gateService).open("dashboard", "Anna");
    }

    //HAS-483: the entrance panel opens without a member
    @Test
    void should_open_the_gate_from_the_panel() {
        when(gateService.open("panel", null)).thenReturn(Mono.empty());

        webTestClient.post().uri("/open?source=panel").exchange().expectStatus().isNoContent();

        verify(gateService).open("panel", null);
    }

    //the screen opens the gate by the unlock of the station, never over HTTP; the member is checked by the service
    @Test
    void should_refuse_another_source() {
        webTestClient.post().uri("/open?source=screen&member=Anna").exchange().expectStatus().isBadRequest();
        webTestClient.post().uri("/open").exchange().expectStatus().isBadRequest();

        verify(gateService, never()).open(any(), any());
    }

    //the dashboard without a member, refused by the service (HAS-483) - a 400, never a 500
    @Test
    void should_answer_a_missing_member_of_the_dashboard_with_a_bad_request() {
        when(gateService.open("dashboard", null))
            .thenReturn(Mono.error(new ServerWebInputException("member is required for the dashboard")));

        webTestClient.post().uri("/open?source=dashboard").exchange().expectStatus().isBadRequest();
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
        when(gateService.open("dashboard", "Anna")).thenReturn(Mono.error(refusal));

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

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 11, 18, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 11, 19, 0, 0);

    //HAS-450: the timeline in the time of the house, a field not of its kind left out
    @Test
    void should_read_the_events_of_a_period() {
        when(gateEventService.events(FROM, TO)).thenReturn(Flux.just(
            new GateEvent(GateEvent.COMMAND, LocalDateTime.of(2026, 11, 18, 10, 56, 9), null, null, null, null, "screen",
                null, "opened"),
            new GateEvent(GateEvent.UNLOCK, LocalDateTime.of(2026, 11, 18, 10, 56, 9), null, 1, "HOUSEHOLDER",
                "10010110001", null, null, null)));

        webTestClient.get().uri("/events?from=2026-11-18T00:00:00&to=2026-11-19T00:00:00").exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.length()").isEqualTo(2)
            .jsonPath("$[0].kind").isEqualTo("command")
            .jsonPath("$[0].source").isEqualTo("screen")
            .jsonPath("$[0].member").doesNotExist()
            .jsonPath("$[0].door").doesNotExist()
            .jsonPath("$[1].kind").isEqualTo("unlock")
            .jsonPath("$[1].at").isEqualTo("2026-11-18T10:56:09")
            .jsonPath("$[1].screen").isEqualTo("10010110001");
    }

    @Test
    void should_answer_an_invalid_period_with_its_code() {
        when(gateEventService.events(TO, FROM)).thenReturn(Flux.error(new GateException.InvalidRange("from must be before to")));

        webTestClient.get().uri("/events?from=2026-11-19T00:00:00&to=2026-11-18T00:00:00").exchange()
            .expectStatus().isBadRequest()
            .expectBody().jsonPath("$.errors[0].code").isEqualTo("INVALID_RANGE");
    }

    @Test
    void should_refuse_a_period_without_both_ends_or_not_a_time() {
        webTestClient.get().uri("/events?from=2026-11-18T00:00:00").exchange().expectStatus().isBadRequest();
        webTestClient.get().uri("/events?from=yesterday&to=2026-11-18T00:00:00").exchange().expectStatus().isBadRequest();

        verify(gateEventService, never()).events(any(), any());
    }
}
