package cloud.cholewa.gate.intercom;

import cloud.cholewa.gate.database.IntercomEventEntity;
import cloud.cholewa.gate.database.IntercomEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessResourceException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IntercomEventServiceTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 17, 16, 13, 37);
    private static final IntercomEventEntity UNLOCK = IntercomEventEntity.unlock(AT, AT, "HOUSEHOLDER",
        "494848494849494848484900", "10010110001", 1);

    private final IntercomEventRepository repository = mock(IntercomEventRepository.class);
    private final IntercomEventService service = new IntercomEventService(repository);

    @Test
    void should_store_an_event() {
        when(repository.save(any())).thenReturn(Mono.just(UNLOCK));

        service.store(UNLOCK).as(StepVerifier::create).verifyComplete();
    }

    //the attributes the bridge publishes again are the same unlock: no second row, and no failure of the listener
    @Test
    void should_complete_an_unlock_already_stored() {
        when(repository.save(any())).thenReturn(Mono.error(new DuplicateKeyException(
            "duplicate key value violates unique constraint \"intercom_event_unlock_uq\"")));

        service.store(UNLOCK).as(StepVerifier::create).verifyComplete();
    }

    //another refusal of the schema refuses the same row again: no retry, the failure goes to the listener
    @Test
    void should_hand_back_another_refusal_of_the_schema_at_once() {
        final AtomicInteger attempts = new AtomicInteger();
        when(repository.save(any())).thenReturn(Mono.defer(() -> {
            attempts.incrementAndGet();
            return Mono.error(new DataIntegrityViolationException("new row violates check constraint"));
        }));

        service.store(UNLOCK).as(StepVerifier::create).verifyError(DataIntegrityViolationException.class);
        assertThat(attempts).hasValue(1);
    }

    //a database out of reach for a moment is tried twice more, with a backoff
    @Test
    void should_try_a_failed_write_twice_more() {
        final AtomicInteger attempts = new AtomicInteger();
        when(repository.save(any())).thenReturn(Mono.defer(() -> attempts.incrementAndGet() < 3
            ? Mono.error(new TransientDataAccessResourceException("connection refused"))
            : Mono.just(UNLOCK)));

        StepVerifier.withVirtualTime(() -> service.store(UNLOCK))
            .thenAwait(Duration.ofSeconds(10))
            .verifyComplete();
        assertThat(attempts).hasValue(3);
    }

    //the write is part of the chain of the listener: a database that hangs gives up after 30 s
    @Test
    void should_give_up_a_write_that_hangs() {
        when(repository.save(any())).thenReturn(Mono.never());

        StepVerifier.withVirtualTime(() -> service.store(UNLOCK))
            .thenAwait(IntercomEventService.WRITE_TIMEOUT)
            .verifyError(TimeoutException.class);
    }
}
