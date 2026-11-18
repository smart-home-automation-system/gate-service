package cloud.cholewa.gate.gate;

import cloud.cholewa.gate.config.GateProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

//An unlock of the door station that opens the gate (HAS-449; the owner, 2026-11-17: every unlock from the indoor screen
//is an opening of the gate, with or without a call): unlock_type HOUSEHOLDER from a number of gate.screens. It does not
//wait for the write of the event - the gate opens at once, whatever the database does. What keeps a repeat from opening
//it again, without the database: an unlock older than gate.screen.max-age opens nothing (it waited in the queue through a
//restart of the service, or the bridge published it again after its own restart), and one not newer than the last
//unlock that opened the gate is the same unlock heard again. Any other unlock (a card, a code, another source) is
//stored by the router and opens nothing
@Slf4j
@Component
@RequiredArgsConstructor
public class ScreenOpening {

    static final String HOUSEHOLDER = "HOUSEHOLDER";

    private final GateOpener gateOpener;
    private final GateProperties properties;
    private final Clock clock;

    //the time the station gave the last unlock that opened the gate
    private final AtomicReference<LocalDateTime> lastOpened = new AtomicReference<>();

    public Mono<Void> onUnlock(final String unlockType, final String number, final LocalDateTime occurredAt) {
        return Mono.defer(() -> {
            if (!HOUSEHOLDER.equals(unlockType) || !properties.screens().contains(number)) {
                return Mono.empty();
            }
            //instants in the zone of the house, so the age is right on the night the clocks change too
            final Duration age = Duration.between(occurredAt.atZone(clock.getZone()).toInstant(), clock.instant());
            if (age.compareTo(properties.screen().maxAge()) > 0) {
                return Mono.fromRunnable(() -> log.info(
                    "An unlock of the screen {} of {} is {} s old - the gate is not opened", number, occurredAt,
                    age.toSeconds()));
            }
            if (!claim(occurredAt)) {
                return Mono.fromRunnable(() -> log.info(
                    "The unlock of the screen {} of {} opened the gate before - not again", number, occurredAt));
            }
            return gateOpener.open(GateOpener.Source.SCREEN, null)
                .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                    "The unlock of the screen {} of {} did not open the gate: {}", number, occurredAt,
                    failure.getMessage())));
        });
    }

    private boolean claim(final LocalDateTime occurredAt) {
        while (true) {
            final LocalDateTime last = lastOpened.get();
            if (last != null && !occurredAt.isAfter(last)) {
                return false;
            }
            if (lastOpened.compareAndSet(last, occurredAt)) {
                return true;
            }
        }
    }
}
