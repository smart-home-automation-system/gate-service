package cloud.cholewa.gate.garage;

import cloud.cholewa.gate.database.GateCommandEntity;
import cloud.cholewa.gate.database.GateCommandRepository;
import cloud.cholewa.gate.error.GateException;
import cloud.cholewa.gate.gate.GarageGate;
import cloud.cholewa.gate.gate.GateRegistry;
import cloud.cholewa.gate.gate.GateRegistry.GarageGateState;
import cloud.cholewa.shelly.http.ShellyGen1Client;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

//The pulse of a garage gate from the dashboard (HAS-474): one "turn on" of the relay of its Shelly 1 - its auto_off ends
//the pulse - sent once and never retried (no answer: it may have moved), with NO repeat window (owner, 2026-11-18: a
//second press stops the gate half-way, it must go out at once). Nothing is sent to a gate the registry does not hold or
//whose relay does not switch itself off. Every pulse is logged in gate_command with the id of its gate; a failed write
//never fails the pulse. The drive of a garage gate opens, stops and closes on the same pulse, so whether it reacted is
//told by its sensor: a pulse its sensor does not answer within CONFIRM_WITHIN is not confirmed; a second pulse within that
//time cancels the check - a gate stopped half-way reports nothing new
@Slf4j
@Component
@RequiredArgsConstructor
public class GaragePulser {

    static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(10);
    static final Duration WRITE_TIMEOUT = Duration.ofSeconds(30);
    static final Duration CONFIRM_WITHIN = Duration.ofSeconds(60);

    private final GateRegistry gateRegistry;
    private final GarageSensors garageSensors;
    private final ShellyGen1Client shellyGen1Client;
    private final GateCommandRepository repository;
    private final Clock clock;

    //the last pulse of every garage gate the Shelly took, in memory
    private final Map<Long, Pulse> pulses = new ConcurrentHashMap<>();

    //when it was sent, what the sensor had last heard then, and whether a second pulse cancelled its check
    record Pulse(Instant at, LocalDateTime sinceBefore, boolean cancelled) {
    }

    public Mono<Void> pulse(final long id, final String member) {
        return Mono.defer(() -> {
            final LocalDateTime now = LocalDateTime.now(clock);
            final GarageGateState state = gateRegistry.garage(id).orElse(null);
            if (state == null) {
                return Mono.error(new GateException.GateNotFound(id));
            }
            if (!state.safe()) {
                return logCommand(id, now, member, GateCommandEntity.UNAVAILABLE)
                    .then(Mono.error(new GateException.GateUnavailable(
                        "The relay of the garage gate " + id + " does not switch itself off (auto_off " + state.autoOff() + ")")));
            }
            final GarageGate gate = state.gate();
            return shellyGen1Client.relay(gate.address(), gate.channel(), true)
                .timeout(COMMAND_TIMEOUT)
                .then(Mono.defer(() -> {
                    remember(gate);
                    log.info("The garage gate {} ({}) pulsed by {}", gate.id(), gate.name(), member);
                    return logCommand(id, now, member, GateCommandEntity.PULSED);
                }))
                .onErrorResume(failure -> !(failure instanceof GateException), failure -> {
                    log.warn("The Shelly of the garage gate {} did not take the pulse - it may have moved all the same: {}",
                        id, failure.toString());
                    return logCommand(id, now, member, GateCommandEntity.FAILED)
                        .then(Mono.error(new GateException.DeviceFailed(failure.getClass().getSimpleName())));
                });
        });
    }

    //a pulse within CONFIRM_WITHIN of the one before cancels the check of both
    private void remember(final GarageGate gate) {
        final Instant now = clock.instant();
        final LocalDateTime since = garageSensors.stateOf(gate).since();
        pulses.compute(gate.id(), (id, before) -> new Pulse(now, since,
            before != null && now.isBefore(before.at().plus(CONFIRM_WITHIN))));
    }

    /**
     * Whether the last pulse of the gate was answered by its sensor: null - no pulse since the start, its check cancelled
     * by a second pulse, or still within CONFIRM_WITHIN without an answer; true - the sensor heard a change since; false -
     * none within CONFIRM_WITHIN.
     */
    public Boolean lastCommandConfirmed(final GarageGate gate) {
        final Pulse pulse = pulses.get(gate.id());
        if (pulse == null || pulse.cancelled()) {
            return null;
        }
        if (!Objects.equals(garageSensors.stateOf(gate).since(), pulse.sinceBefore())) {
            return Boolean.TRUE;
        }
        return clock.instant().isBefore(pulse.at().plus(CONFIRM_WITHIN)) ? null : Boolean.FALSE;
    }

    private Mono<Void> logCommand(final long id, final LocalDateTime at, final String member, final String outcome) {
        return repository.save(GateCommandEntity.ofGarage(id, at, "DASHBOARD", member, outcome))
            .timeout(WRITE_TIMEOUT)
            .then()
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                "The pulse of the garage gate {} ({}) could not be logged: {}", id, outcome, failure.toString())));
    }
}
