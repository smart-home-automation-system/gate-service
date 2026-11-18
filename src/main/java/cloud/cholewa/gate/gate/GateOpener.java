package cloud.cholewa.gate.gate;

import cloud.cholewa.gate.config.GateProperties;
import cloud.cholewa.gate.database.GateCommandEntity;
import cloud.cholewa.gate.database.GateCommandRepository;
import cloud.cholewa.gate.error.GateException;
import cloud.cholewa.shelly.http.ShellyGen1Client;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

//Opens the gate (HAS-449): one "turn on" of the relay of its Shelly 1 - the auto_off of the relay ends the pulse - sent
//once and never retried: no answer means the gate may have moved. The gate is step-by-step (a second pulse while it moves
//stops or reverses it), so an opening within gate.open.ignore-repeat-for of the last command sent is refused - the slot
//is taken before the command, so two requests at once send one pulse. Nothing is sent while the gate is not known or its
//relay does not switch itself off. Every opening is logged in gate_command; a failed write of that row never fails the
//opening
@Slf4j
@Component
@RequiredArgsConstructor
public class GateOpener {

    public enum Source { SCREEN, DASHBOARD }

    //the Shelly answers within its response timeout; this bounds a call that hangs below it
    static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(10);
    static final Duration WRITE_TIMEOUT = Duration.ofSeconds(30);

    private final GateRegistry gateRegistry;
    private final ShellyGen1Client shellyGen1Client;
    private final GateCommandRepository repository;
    private final GateProperties properties;
    private final Clock clock;

    //the instant the last command was sent to the Shelly, whatever it came to - an instant, so the repeat is judged the
    //same on the night the clocks go back
    private final AtomicReference<Instant> lastSent = new AtomicReference<>();

    public Mono<Void> open(final Source source, final String member) {
        return Mono.defer(() -> {
            final Instant instant = clock.instant();
            final LocalDateTime now = LocalDateTime.ofInstant(instant, clock.getZone());
            final GateRegistry.GateState gate = gateRegistry.current().orElse(null);
            if (gate == null || !gate.safe()) {
                return logCommand(now, source, member, GateCommandEntity.UNAVAILABLE)
                    .then(Mono.error(new GateException.GateUnavailable(gate == null
                        ? "The registry knows no gate"
                        : "The relay of the gate does not switch itself off (auto_off " + gate.autoOff() + ")")));
            }
            if (!reserve(instant)) {
                return logCommand(now, source, member, GateCommandEntity.TOO_SOON)
                    .then(Mono.error(new GateException.OpenTooSoon(properties.open().ignoreRepeatFor().toSeconds())));
            }
            final GateDevice device = gate.device();
            return shellyGen1Client.relay(device.address(), device.channel(), true)
                .timeout(COMMAND_TIMEOUT)
                .then(Mono.defer(() -> {
                    log.info("The gate {} opened from {}{}", device.id(), source, member == null ? "" : " by " + member);
                    return logCommand(now, source, member, GateCommandEntity.OPENED);
                }))
                .onErrorResume(failure -> !(failure instanceof GateException), failure -> {
                    log.warn("The Shelly of the gate {} did not take the opening from {} - it may have moved all the same: {}",
                        device.id(), source, failure.toString());
                    return logCommand(now, source, member, GateCommandEntity.FAILED)
                        .then(Mono.error(new GateException.DeviceFailed(failure.getClass().getSimpleName())));
                });
        });
    }

    //the slot of a command: refused while the last command sent is younger than ignore-repeat-for
    private boolean reserve(final Instant now) {
        final Duration repeat = properties.open().ignoreRepeatFor();
        while (true) {
            final Instant last = lastSent.get();
            if (last != null && now.isBefore(last.plus(repeat))) {
                return false;
            }
            if (lastSent.compareAndSet(last, now)) {
                return true;
            }
        }
    }

    private Mono<Void> logCommand(final LocalDateTime at, final Source source, final String member, final String outcome) {
        return repository.save(GateCommandEntity.of(at, source.name(), member, outcome))
            .timeout(WRITE_TIMEOUT)
            .then()
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                "The command of the gate ({} from {}) could not be logged: {}", outcome, source, failure.toString())));
    }
}
