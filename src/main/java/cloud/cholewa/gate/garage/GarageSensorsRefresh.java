package cloud.cholewa.gate.garage;

import cloud.cholewa.commons.scheduling.ScheduledPass;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

//The sensors of the garage gates and their gateways read every five minutes (HAS-473). The first pass waits a little
//after the start: the gates that name the sensors are read by GateRegistry at the start, in a pass of its own. Spring
//subscribes again to the same Mono for every run, which is why the read itself is deferred in GarageSensors.refresh().
//Bounded: the registry and every BTHome gateway page by page, each call with its own timeout - two minutes is a pass
//that hangs; the state read so far stays. Scheduling is off under the test profile
@Component
@RequiredArgsConstructor
public class GarageSensorsRefresh {

    static final Duration LIMIT = Duration.ofMinutes(2);

    private final GarageSensors garageSensors;

    @Scheduled(initialDelayString = "PT20S", fixedDelayString = "PT5M")
    Mono<Void> refresh() {
        return ScheduledPass.bounded("garage sensors refresh", LIMIT, garageSensors::refresh);
    }
}
