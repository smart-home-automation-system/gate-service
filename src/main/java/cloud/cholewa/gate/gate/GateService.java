package cloud.cholewa.gate.gate;

import cloud.cholewa.gate.api.model.GateReply;
import cloud.cholewa.gate.database.GateCommandEntity;
import cloud.cholewa.gate.database.GateCommandRepository;
import cloud.cholewa.gate.database.IntercomEventEntity;
import cloud.cholewa.gate.database.IntercomEventRepository;
import cloud.cholewa.gate.intercom.BridgeAvailability;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

//What the API does (HAS-449): the opening from the dashboard - the member checked against the household profiles of
//database-service, then the gate opened as GateOpener does - and the read of the gate. The member is stored as the
//registry spells the name; the case of the name in the request does not matter, as the registry keeps names unique
//ignoring it
@Slf4j
@Service
@RequiredArgsConstructor
public class GateService {

    private static final String PANEL = "panel";

    private final HouseholdMembers householdMembers;
    private final GateOpener gateOpener;
    private final GateRegistry gateRegistry;
    private final BridgeAvailability bridgeAvailability;
    private final IntercomEventRepository intercomEvents;
    private final GateCommandRepository gateCommands;

    //POST /open (HAS-483): the entrance panel opens without a member (one sent with it is not stored), the dashboard only
    //with a member of the household
    public Mono<Void> open(final String source, final String member) {
        return Mono.just(source)
            .filter(PANEL::equals)
            .map(panel -> gateOpener.open(GateOpener.Source.PANEL, null))
            .defaultIfEmpty(Mono.defer(() -> openFromDashboard(member)))
            .flatMap(Function.identity());
    }

    public Mono<Void> openFromDashboard(final String member) {
        return Mono.justOrEmpty(member)
            .filter(name -> !name.isBlank())
            .switchIfEmpty(Mono.error(() -> new ServerWebInputException("member is required for the dashboard")))
            .flatMap(householdMembers::known)
            .flatMap(name -> gateOpener.open(GateOpener.Source.DASHBOARD, name));
    }

    public Mono<GateReply> read() {
        final GateReply.Gate gate = gateRegistry.current()
            .map(state -> new GateReply.Gate(state.device().id(), state.reachable(), state.safe(), state.autoOff(),
                state.checkedAt()))
            .orElse(null);
        final GateReply.Bridge bridge = bridgeAvailability.latest()
            .map(report -> new GateReply.Bridge(report.online(), report.at()))
            .orElse(null);
        return Mono.zip(lastRing(), lastOpening())
            .map(last -> new GateReply(gate, bridge, last.getT1().orElse(null), last.getT2().orElse(null)));
    }

    private Mono<Optional<GateReply.Ring>> lastRing() {
        return intercomEvents.findFirstByKindOrderByOccurredAtDesc(IntercomEventEntity.RING)
            .map(ring -> Optional.of(new GateReply.Ring(ring.occurredAt(), ring.deviceNumber())))
            .defaultIfEmpty(Optional.empty());
    }

    private Mono<Optional<GateReply.Opening>> lastOpening() {
        return gateCommands.findFirstByOutcomeOrderByAtDesc(GateCommandEntity.OPENED)
            .map(opening -> Optional.of(new GateReply.Opening(opening.at(), opening.source().toLowerCase(Locale.ROOT),
                opening.member())))
            .defaultIfEmpty(Optional.empty());
    }
}
