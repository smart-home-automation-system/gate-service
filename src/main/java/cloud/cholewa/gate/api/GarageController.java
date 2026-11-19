package cloud.cholewa.gate.api;

import cloud.cholewa.gate.api.model.GarageGateReply;
import cloud.cholewa.gate.garage.GarageGateService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

//The garage gates (HAS-473): GET /garage - every garage gate of the registry with what its sensors say, by id, an empty
//list before the first read of the registry; GET /garage/{id} - one, 404 NOT_FOUND_GATE for an id that is no garage
//gate. POST /garage/{id}/pulse?source=dashboard&member= (HAS-474): 204 once the Shelly took the pulse; 400
//UNKNOWN_MEMBER, 404 NOT_FOUND_GATE, 502 DEVICE_FAILED (it may have moved all the same), 503 GATE_UNAVAILABLE (a relay
//that does not switch itself off), 502 REGISTRY_UNAVAILABLE
@RestController
@RequestMapping("/garage")
@RequiredArgsConstructor
public class GarageController {

    private final GarageGateService garageGateService;

    @GetMapping
    public Mono<List<GarageGateReply>> all() {
        return garageGateService.all().collectList();
    }

    @GetMapping("/{id}")
    public Mono<GarageGateReply> one(@PathVariable final long id) {
        return garageGateService.one(id);
    }

    //HAS-474: one pulse - opens, stops or closes the gate alike; no question, no repeat window (owner, 2026-11-18)
    @PostMapping("/{id}/pulse")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> pulse(
        @PathVariable final long id,
        @RequestParam @Pattern(regexp = "dashboard", message = "source must be dashboard") final String source,
        @RequestParam @NotBlank @Size(max = 50) final String member
    ) {
        return garageGateService.pulse(id, member);
    }
}
