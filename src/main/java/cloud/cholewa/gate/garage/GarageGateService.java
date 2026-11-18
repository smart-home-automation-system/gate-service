package cloud.cholewa.gate.garage;

import cloud.cholewa.gate.api.model.GarageGateReply;
import cloud.cholewa.gate.error.GateException;
import cloud.cholewa.gate.gate.GateRegistry;
import cloud.cholewa.gate.gate.GateRegistry.GarageGateState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

//The read of the garage gates (HAS-473): the gates of the registry with what their sensors say, by id. Memory only - no
//query: the state is held by GarageSensors, the gates by GateRegistry
@Service
@RequiredArgsConstructor
public class GarageGateService {

    private final GateRegistry gateRegistry;
    private final GarageSensors garageSensors;

    public Flux<GarageGateReply> all() {
        return Flux.defer(() -> Flux.fromIterable(gateRegistry.garages())).map(this::reply);
    }

    public Mono<GarageGateReply> one(final long id) {
        return Mono.defer(() -> Mono.justOrEmpty(gateRegistry.garage(id)))
            .map(this::reply)
            .switchIfEmpty(Mono.error(() -> new GateException.GateNotFound(id)));
    }

    private GarageGateReply reply(final GarageGateState gate) {
        final GarageSensors.GarageReading reading = garageSensors.stateOf(gate.gate());
        return new GarageGateReply(gate.gate().id(), gate.gate().name(), reading.state(), reading.since(),
            reading.confirmedAt(), gate.reachable(), gate.safe());
    }
}
