package cloud.cholewa.gate.api;

import cloud.cholewa.gate.api.model.GateEvent;
import cloud.cholewa.gate.api.model.GateReply;
import cloud.cholewa.gate.events.GateEventService;
import cloud.cholewa.gate.gate.GateService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;

//The gate (HAS-449): open it from the dashboard and read it. POST /open is a write to the house with no question by the
//owner's choice (2026-11-16 - as the button of the indoor screen): 204 once the Shelly took the command, 400
//UNKNOWN_MEMBER, 409 OPEN_TOO_SOON, 502 DEVICE_FAILED (it may have moved all the same), 503 GATE_UNAVAILABLE, 502
//REGISTRY_UNAVAILABLE. The only source of the API is the dashboard - the screen opens the gate by the unlock of the door
//station, never over HTTP. GET /events (HAS-450) - the timeline of the station and of the gate in a period of at most
//31 days, the newest first; 400 INVALID_RANGE
@RestController
@RequestMapping
@RequiredArgsConstructor
public class GateController {

    private final GateService gateService;
    private final GateEventService gateEventService;

    @PostMapping("/open")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> open(
        @RequestParam @Pattern(regexp = "dashboard", message = "source must be dashboard") final String source,
        @RequestParam @NotBlank @Size(max = 50) final String member
    ) {
        return gateService.openFromDashboard(member);
    }

    @GetMapping
    public Mono<GateReply> read() {
        return gateService.read();
    }

    @GetMapping("/events")
    public Mono<List<GateEvent>> events(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) final LocalDateTime from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) final LocalDateTime to
    ) {
        //collected, not streamed: a database failure halfway is an error answer, never a cut array behind a 200
        return gateEventService.events(from, to).collectList();
    }
}
