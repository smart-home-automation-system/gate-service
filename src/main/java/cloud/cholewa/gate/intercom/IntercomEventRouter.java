package cloud.cholewa.gate.intercom;

import cloud.cholewa.gate.config.IntercomProperties;
import cloud.cholewa.gate.database.IntercomEventEntity;
import cloud.cholewa.gate.gate.ScreenOpening;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

//Turns the topics of the Hikvision bridge into the events of the service (HAS-448). The queue gets every event of every
//device of the house, about 130 a minute: what is not a topic of the bridge for the station of this service is dropped
//first, without a word. Of the bridge (deployment-tools/scripts/hikvision/devices.md):
//- Call-state attributes {"device_number": ...} come right before "ringing" - the button, kept for the ring;
//- Call-state "ringing" is a ring, "dismissed" the end of a call (not answered, rejected or hung up alike), "idle"
//  nothing - the bridge says it after every end and 60 s after every ring;
//- the attributes of Door-<n>-relay are an unlock of the door n: one pulse of the relay (ON, then OFF), stored once
//  and handed to ScreenOpening, which opens the gate for an unlock of an indoor screen (the owner, 2026-11-17: every
//  unlock is an opening of the gate, HAS-449); the relay states and Door-unlocked say the same again and are not read;
//- <entity>.availability is the availability of the bridge;
//- the motion triggers, the image, the commands and the rest of the entities cause nothing.
@Slf4j
@Component
@RequiredArgsConstructor
public class IntercomEventRouter {

    static final String RINGING = "ringing";
    static final String DISMISSED = "dismissed";
    static final String IDLE = "idle";

    private static final String AVAILABILITY = "availability";
    private static final String ATTRIBUTES = "attributes";
    private static final String STATE = "state";
    private static final String CALL_STATE = "Call-state";
    private static final Pattern DOOR_RELAY = Pattern.compile("Door-(\\d)-relay");
    //last_unlocked as the bridge writes it: the wall time of its container
    private static final DateTimeFormatter LAST_UNLOCKED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final IntercomEventService service;
    private final ScreenOpening screenOpening;
    private final BridgeAvailability availability;
    private final IntercomProperties properties;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    //the button of the last ring the attributes named; the attributes come right before "ringing"
    private final AtomicReference<String> lastButton = new AtomicReference<>();

    record CallAttributes(@JsonProperty("device_number") String deviceNumber) {
    }

    record UnlockAttributes(
        @JsonProperty("control_source") String controlSource,
        @JsonProperty("number") String number,
        @JsonProperty("unlock_type") String unlockType,
        @JsonProperty("last_unlocked") String lastUnlocked
    ) {
    }

    public Mono<Void> route(final String routingKey, final byte[] body) {
        return IntercomTopic.of(routingKey)
            .filter(topic -> properties.device().equals(topic.device()))
            .map(topic -> Mono.defer(() -> routeTopic(topic, new String(body, StandardCharsets.UTF_8))))
            .orElseGet(Mono::empty);
    }

    private Mono<Void> routeTopic(final IntercomTopic topic, final String payload) {
        final LocalDateTime now = LocalDateTime.now(clock);
        if (AVAILABILITY.equals(topic.suffix())) {
            return Mono.fromRunnable(() -> availability.report(topic.entity(), payload, now));
        }
        if (CALL_STATE.equals(topic.entity())) {
            return callState(topic.suffix(), payload, now);
        }
        final Matcher door = DOOR_RELAY.matcher(topic.entity());
        if (door.matches() && ATTRIBUTES.equals(topic.suffix())) {
            return unlock(Integer.parseInt(door.group(1)), payload, now);
        }
        return Mono.empty();
    }

    private Mono<Void> callState(final String suffix, final String payload, final LocalDateTime now) {
        if (ATTRIBUTES.equals(suffix)) {
            return read(payload, CallAttributes.class, "Call-state attributes")
                .doOnNext(attributes -> lastButton.set(attributes.deviceNumber()))
                .then();
        }
        if (!STATE.equals(suffix)) {
            return Mono.empty();
        }
        return switch (payload) {
            case RINGING -> service.store(IntercomEventEntity.ring(now, lastButton.getAndSet(null)));
            case DISMISSED -> service.store(IntercomEventEntity.dismissed(now));
            case IDLE -> Mono.empty();
            default -> Mono.fromRunnable(() -> log.warn("A call state of the station this service does not know: {}",
                payload));
        };
    }

    private Mono<Void> unlock(final int door, final String payload, final LocalDateTime now) {
        return read(payload, UnlockAttributes.class, "Door-" + door + "-relay attributes")
            .flatMap(attributes -> occurredAt(attributes.lastUnlocked())
                //the gate opens at once (HAS-449), whatever the write of the event comes to
                .map(occurredAt -> Mono.when(
                    service.store(IntercomEventEntity.unlock(occurredAt, now, attributes.unlockType(),
                        attributes.controlSource(), attributes.number(), door)),
                    screenOpening.onUnlock(attributes.unlockType(), attributes.number(), occurredAt)))
                .orElseGet(() -> Mono.fromRunnable(() -> log.warn(
                    "An unlock of the door {} without a time the service can read: {}", door, payload))));
    }

    //the wall time of the bridge, moved into the time of the house
    private Optional<LocalDateTime> occurredAt(final String lastUnlocked) {
        if (lastUnlocked == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDateTime.parse(lastUnlocked, LAST_UNLOCKED)
                .atZone(properties.bridgeZone())
                .withZoneSameInstant(clock.getZone())
                .toLocalDateTime());
        } catch (DateTimeParseException exception) {
            return Optional.empty();
        }
    }

    //a body that is not what its topic carries is logged and the message completed - another delivery would carry the
    //same body
    private <T> Mono<T> read(final String payload, final Class<T> type, final String what) {
        return Mono.fromCallable(() -> jsonMapper.readValue(payload, type))
            .onErrorResume(JacksonException.class, exception -> Mono.fromRunnable(() ->
                log.warn("{} of the station that are not JSON of their kind: {}", what, payload)));
    }
}
