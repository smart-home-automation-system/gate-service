package cloud.cholewa.gate.intercom;

import cloud.cholewa.gate.database.IntercomEventEntity;
import cloud.cholewa.gate.database.IntercomEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;

//Stores an event of the door station. The write is part of the chain of the listener, so it is bounded: a database that
//hangs must not hold a message for ever. A failure another attempt can change - the database out of reach for a moment
//- is tried twice more with a backoff; a refusal of the schema is not (it refuses the same row again), and an unlock
//refused by its unique index is the same unlock heard again - complete, no second row
@Slf4j
@Service
@RequiredArgsConstructor
public class IntercomEventService {

    static final Duration WRITE_TIMEOUT = Duration.ofSeconds(30);
    private static final int RETRIES = 2;
    private static final Duration FIRST_BACKOFF = Duration.ofSeconds(1);
    private static final String UNLOCK_UNIQUE_INDEX = "intercom_event_unlock_uq";

    private final IntercomEventRepository repository;

    public Mono<Void> store(final IntercomEventEntity event) {
        return Mono.defer(() -> repository.save(event))
            .retryWhen(Retry.backoff(RETRIES, FIRST_BACKOFF)
                .filter(throwable -> !(throwable instanceof DataIntegrityViolationException)))
            .timeout(WRITE_TIMEOUT)
            .doOnNext(saved -> log.info("Intercom event stored: {} at {}{}", saved.kind(), saved.occurredAt(),
                describe(saved)))
            .onErrorResume(DataIntegrityViolationException.class, exception -> isRepeatedUnlock(exception)
                ? Mono.fromRunnable(() -> log.info("Intercom unlock of the door {} at {} already stored",
                    event.door(), event.occurredAt()))
                : Mono.error(exception))
            .then();
    }

    private static boolean isRepeatedUnlock(final DataIntegrityViolationException exception) {
        final String message = exception.getMessage();
        return message != null && message.contains(UNLOCK_UNIQUE_INDEX);
    }

    private static String describe(final IntercomEventEntity event) {
        return switch (event.kind()) {
            case IntercomEventEntity.RING -> ", button " + event.deviceNumber();
            case IntercomEventEntity.UNLOCK -> ", door " + event.door() + " by " + event.unlockType() + " from "
                + event.number();
            default -> "";
        };
    }
}
