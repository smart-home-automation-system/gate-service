package cloud.cholewa.gate.garage;

import cloud.cholewa.gate.api.model.GarageGateReply;
import cloud.cholewa.gate.error.GateException;
import cloud.cholewa.gate.gate.GateRegistry;
import cloud.cholewa.gate.gate.GateRegistry.GarageGateState;
import cloud.cholewa.gate.gate.HouseholdMembers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

//The garage gates (HAS-473, HAS-474): the read - the gates of the registry with what their sensors say and whether the
//last pulse was answered, by id, memory only - and the pulse from the dashboard, by a member of the household
@Service
@RequiredArgsConstructor
public class GarageGateService {

    private final GateRegistry gateRegistry;
    private final GarageSensors garageSensors;
    private final GaragePulser garagePulser;
    private final HouseholdMembers householdMembers;

    public Flux<GarageGateReply> all() {
        return Flux.defer(() -> Flux.fromIterable(gateRegistry.garages())).map(this::reply);
    }

    public Mono<GarageGateReply> one(final long id) {
        return Mono.defer(() -> Mono.justOrEmpty(gateRegistry.garage(id)))
            .map(this::reply)
            .switchIfEmpty(Mono.error(() -> new GateException.GateNotFound(id)));
    }

    //the member checked first: an unknown one sends nothing
    public Mono<Void> pulse(final long id, final String member) {
        return householdMembers.known(member).flatMap(name -> garagePulser.pulse(id, name));
    }

    private GarageGateReply reply(final GarageGateState gate) {
        final GarageSensors.GarageReading reading = garageSensors.stateOf(gate.gate());
        return new GarageGateReply(gate.gate().id(), gate.gate().name(), reading.state(), reading.since(),
            reading.confirmedAt(), gate.reachable(), gate.safe(), garagePulser.lastCommandConfirmed(gate.gate()).orElse(null));
    }
}
