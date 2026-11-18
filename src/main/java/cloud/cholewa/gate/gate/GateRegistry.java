package cloud.cholewa.gate.gate;

import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.gate.registry.DeviceRegistryClient;
import cloud.cholewa.home.model.SmartDevice;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

//The Shelly of the gate (HAS-449): the device of the registry (the kind gate) and the auto_off of its relay, read at the
//start and every five minutes. A failed read of the registry keeps the gate read before (a WARN); a failed read of the
//device makes it unreachable and its auto_off not known - so not safe to command. Until the first read nothing is
//known. The registry holds one gate; with more, the lowest id is driven and the rest named in a WARN. The door station
//(the kind intercom, HAS-452) is read in the same pass, on its own: its id and name are the subject of a ring told to the
//household; a failed read keeps the one read before, the lowest id of more is taken. A gate with openingSensors is a garage
//gate (HAS-473): the driveway gate is the lowest id of the gates without one, the garage gates are kept apart with the
//auto_off of each relay - one read of the gates for both
@Slf4j
@Component
@RequiredArgsConstructor
public class GateRegistry {

    //below the delay of five minutes: a read of the registry and a read of the device, each bounded at 5 s
    static final Duration LIMIT = Duration.ofMinutes(1);

    private final DeviceRegistryClient deviceRegistryClient;
    private final GateSettingsClient gateSettingsClient;
    private final Clock clock;
    private final AtomicReference<GateState> state = new AtomicReference<>();
    private final AtomicReference<IntercomDevice> intercom = new AtomicReference<>();
    private final AtomicReference<List<GarageGateState>> garages = new AtomicReference<>(List.of());

    //a garage gate and what the last read of its Shelly said (HAS-473) - the rule of GateState
    public record GarageGateState(GarageGate gate, Double autoOff, boolean reachable, LocalDateTime checkedAt) {

        public boolean safe() {
            return autoOff != null && autoOff > 0;
        }
    }

    //the gate and what the last read of it said: autoOff null and reachable false when the device did not answer
    public record GateState(GateDevice device, Double autoOff, boolean reachable, LocalDateTime checkedAt) {

        //a relay that switches itself off: only then is "turn on" a pulse and not a permanent signal to the drive
        public boolean safe() {
            return autoOff != null && autoOff > 0;
        }
    }

    @Scheduled(initialDelayString = "PT0S", fixedDelayString = "PT5M")
    Mono<Void> refresh() {
        return ScheduledPass.bounded("gate registry refresh", LIMIT, this::refreshOnce);
    }

    Mono<Void> refreshOnce() {
        return Mono.when(refreshGate(), refreshIntercom());
    }

    private Mono<Void> refreshIntercom() {
        return Mono.defer(deviceRegistryClient::getIntercoms)
            .map(devices -> devices.stream()
                .filter(device -> !Boolean.FALSE.equals(device.getEnabled()))
                .map(IntercomDevice::of)
                .flatMap(Optional::stream)
                .min(Comparator.comparingLong(IntercomDevice::id)))
            .doOnNext(read -> {
                final IntercomDevice before = intercom.getAndSet(read.orElse(null));
                if (!Objects.equals(before, read.orElse(null))) {
                    log.info("The door station of the registry: {}", read.map(IntercomDevice::id).orElse(null));
                }
            })
            .then()
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                "The door station of the registry could not be read, the last one is kept: {}", failure.toString())));
    }

    //the door station of the registry, or nothing before the first read found one
    public Optional<IntercomDevice> currentIntercom() {
        return Optional.ofNullable(intercom.get());
    }

    private Mono<Void> refreshGate() {
        return Mono.defer(deviceRegistryClient::getGates)
            .flatMap(devices -> Mono.when(refreshDriveway(devices), refreshGarages(devices)))
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                "The gates of the registry could not be read, the last ones are kept: {}", failure.toString())));
    }

    //the driveway gate: a gate without a sensor (HAS-473 - one with sensors is a garage gate)
    private Mono<Void> refreshDriveway(final List<SmartDevice> devices) {
        return Mono.just(chosen(devices.stream().filter(device -> !GarageGate.isGarage(device)).toList()))
            .flatMap(gate -> gate.map(this::check).orElseGet(() -> Mono.fromRunnable(this::forget)));
    }

    //the garage gates (HAS-473): each switched-on gate with sensors, with the auto_off of its relay - read one after the
    //other, each with its own failure
    private Mono<Void> refreshGarages(final List<SmartDevice> devices) {
        return Flux.fromIterable(devices)
            .filter(device -> !Boolean.FALSE.equals(device.getEnabled()) && GarageGate.isGarage(device))
            .map(GarageGate::of)
            .flatMap(Mono::justOrEmpty)
            .sort(Comparator.comparingLong(GarageGate::id))
            .concatMap(this::checkGarage)
            .collectList()
            .doOnNext(read -> {
                final List<GarageGateState> before = garages.getAndSet(List.copyOf(read));
                if (!before.stream().map(GarageGateState::gate).toList()
                    .equals(read.stream().map(GarageGateState::gate).toList())) {
                    log.info("The garage gates of the registry: {}", read.stream()
                        .map(state -> state.gate().id() + " " + state.gate().name() + " sensors " + state.gate().sensors())
                        .toList());
                }
            })
            .then();
    }

    private Mono<GarageGateState> checkGarage(final GarageGate gate) {
        return gateSettingsClient.autoOff(gate.address(), gate.channel())
            .map(autoOff -> new GarageGateState(gate, autoOff, true, LocalDateTime.now(clock)))
            .onErrorResume(failure -> {
                log.warn("The Shelly of the garage gate {} at {} did not answer the read of its settings: {}", gate.id(),
                    gate.address(), failure.toString());
                return Mono.just(new GarageGateState(gate, null, false, LocalDateTime.now(clock)));
            })
            .doOnNext(read -> {
                if (!read.safe() && read.reachable()) {
                    log.error("The relay of the garage gate {} has auto_off {} - it is not commanded until it switches "
                        + "itself off", gate.id(), read.autoOff());
                }
            });
    }

    //the garage gates of the registry with what the last read of each said, by id; none before the first read
    public List<GarageGateState> garages() {
        return garages.get();
    }

    public Optional<GarageGateState> garage(final long id) {
        return garages.get().stream().filter(state -> state.gate().id() == id).findFirst();
    }

    //the gate of the registry with what its last read said, or nothing before the first read succeeded
    public Optional<GateState> current() {
        return Optional.ofNullable(state.get());
    }

    private Mono<Void> check(final GateDevice gate) {
        return gateSettingsClient.autoOff(gate.address(), gate.channel())
            .map(autoOff -> new GateState(gate, autoOff, true, LocalDateTime.now(clock)))
            .onErrorResume(failure -> {
                log.warn("The Shelly of the gate {} at {} did not answer the read of its settings: {}", gate.id(),
                    gate.address(), failure.toString());
                return Mono.just(new GateState(gate, null, false, LocalDateTime.now(clock)));
            })
            .doOnNext(this::replace)
            .then();
    }

    private void replace(final GateState read) {
        final GateState before = state.getAndSet(read);
        if (!read.safe() && read.reachable()) {
            //a pulse would be a permanent signal: the gate is not commanded (the alert comes with HAS-453)
            log.error("The relay of the gate {} has auto_off {} - the gate is not commanded until it switches itself off",
                read.device().id(), read.autoOff());
        }
        if (before == null || !before.device().equals(read.device())) {
            log.info("The gate of the registry: {} at {} channel {}, auto_off {}", read.device().id(),
                read.device().address(), read.device().channel(), read.autoOff());
        }
    }

    private void forget() {
        if (state.getAndSet(null) != null) {
            log.warn("The registry knows no gate any more");
        }
    }

    private static Optional<GateDevice> chosen(final List<SmartDevice> devices) {
        final List<GateDevice> gates = devices.stream()
            .filter(device -> !Boolean.FALSE.equals(device.getEnabled()))
            .map(device -> GateDevice.of(device).or(() -> {
                log.warn("Device {} of the registry is no gate the service understands", device.getId());
                return Optional.empty();
            }))
            .flatMap(Optional::stream)
            .sorted(Comparator.comparingLong(GateDevice::id))
            .toList();
        if (gates.size() > 1) {
            log.warn("The registry knows {} gates, {} is driven: {}", gates.size(), gates.getFirst().id(),
                gates.stream().map(GateDevice::id).toList());
        }
        return gates.stream().findFirst();
    }
}
