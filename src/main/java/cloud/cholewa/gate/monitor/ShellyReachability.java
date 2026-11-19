package cloud.cholewa.gate.monitor;

import cloud.cholewa.shelly.http.ShellyGen1Client;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

//Whether the Shelly of each gate answers (HAS-453): its /status read once a pass of the monitor, every device with its own
//failure - a slow one never cuts the others. Judged by whole passes: a Shelly is unreachable since the first pass it did
//not answer, reachable again with the first that it does; JSON that is not the model (a /status without wifi_sta) is a
//failed call. In memory: not known after a start until the first pass. The first failure of an episode is a WARN, its
//end an INFO
@Slf4j
@Component
@RequiredArgsConstructor
public class ShellyReachability {

    static final Duration CALL_TIMEOUT = Duration.ofSeconds(5);

    private final ShellyGen1Client shellyGen1Client;
    private final Clock clock;
    private final Map<Long, Reach> reaches = new ConcurrentHashMap<>();

    /** A Shelly of a gate to read: the registry id of its gate, how the log names it, its address. */
    public record Target(long id, String name, String address) {
    }

    /** What the passes said: since when it does not answer, null while it does. */
    public record Reach(LocalDateTime unreachableSince) {
    }

    //one pass: every Shelly at once, each bounded and with its own failure; the gates no longer listed are forgotten
    public Mono<Void> poll(final List<Target> targets) {
        return Mono.defer(() -> {
            final LocalDateTime now = LocalDateTime.now(clock);
            final Set<Long> listed = targets.stream().map(Target::id).collect(Collectors.toSet());
            reaches.keySet().retainAll(listed);
            return Flux.fromIterable(targets)
                .flatMap(target -> shellyGen1Client.status(target.address())
                    .timeout(CALL_TIMEOUT)
                    .doOnNext(status -> answered(target))
                    .then()
                    .onErrorResume(failure -> Mono.fromRunnable(() -> failed(target, now, failure))))
                .then();
        });
    }

    public Optional<Reach> of(final long id) {
        return Optional.ofNullable(reaches.get(id));
    }

    private void answered(final Target target) {
        final Reach before = reaches.put(target.id(), new Reach(null));
        if (before != null && before.unreachableSince() != null) {
            log.info("The Shelly of {} answers again (not answering since {})", target.name(), before.unreachableSince());
        }
    }

    private void failed(final Target target, final LocalDateTime now, final Throwable failure) {
        final Reach before = reaches.get(target.id());
        if (before == null || before.unreachableSince() == null) {
            reaches.put(target.id(), new Reach(now));
            log.warn("The Shelly of {} at {} did not answer its status: {}", target.name(), target.address(),
                failure.toString());
        }
    }
}
