package cloud.cholewa.gate.api;

import cloud.cholewa.gate.api.model.GarageGateReply;
import cloud.cholewa.gate.garage.GarageGateService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

//The garage gates (HAS-473): GET /garage - every garage gate of the registry with what its sensors say, by id, an empty
//list before the first read of the registry; GET /garage/{id} - one, 404 NOT_FOUND_GATE for an id that is no garage
//gate. Read-only here; the pulse comes with HAS-474
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
}
