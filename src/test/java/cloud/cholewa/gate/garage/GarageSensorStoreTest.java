package cloud.cholewa.gate.garage;

import cloud.cholewa.gate.database.GarageSensorReadingEntity;
import cloud.cholewa.gate.database.GarageSensorReadingRepository;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//HAS-473: nothing of the store may stop what calls it - a write, a read or a delete that fails completes
class GarageSensorStoreTest {

    private static final Instant HEARD = Instant.parse("2026-11-18T18:44:57Z");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-18T18:45:00Z"), ZoneId.of("Europe/Warsaw"));
    private static final LocalDateTime STORED = LocalDateTime.of(2026, 11, 18, 19, 45);

    private final GarageSensorReadingRepository repository = mock(GarageSensorReadingRepository.class);
    private final GarageSensorStore store = new GarageSensorStore(repository, CLOCK);

    @Test
    void should_store_a_reading_in_the_time_of_the_house() {
        when(repository.upsert(69L, true, HEARD, STORED)).thenReturn(Mono.just(1));

        store.save(69L, new GarageSensors.Reading(true, HEARD, STORED)).as(StepVerifier::create).verifyComplete();

        verify(repository).upsert(69L, true, HEARD, STORED);
    }

    @Test
    void should_complete_when_the_database_fails() {
        when(repository.upsert(any(), any(), any(), any())).thenReturn(Mono.error(new IllegalStateException("down")));
        when(repository.findAll()).thenReturn(Flux.error(new IllegalStateException("down")));
        when(repository.deleteOthers(any())).thenReturn(Mono.error(new IllegalStateException("down")));

        store.save(69L, new GarageSensors.Reading(true, HEARD, STORED)).as(StepVerifier::create).verifyComplete();
        store.load().as(StepVerifier::create).verifyComplete();
        store.retain(Set.of(69L)).as(StepVerifier::create).verifyComplete();
    }

    //read back as not confirmed: no gateway has stood for it since the start
    @Test
    void should_load_every_reading_as_not_confirmed() {
        when(repository.findAll()).thenReturn(Flux.just(new GarageSensorReadingEntity(69L, false, HEARD, STORED)));

        store.load().as(StepVerifier::create)
            .expectNext(new GarageSensorStore.Stored(69L, new GarageSensors.Reading(false, HEARD, null)))
            .verifyComplete();
    }

    @Test
    void should_delete_the_readings_of_the_sensors_no_gate_names() {
        when(repository.deleteOthers(Set.of(69L, 70L))).thenReturn(Mono.just(1));

        store.retain(Set.of(69L, 70L)).as(StepVerifier::create).verifyComplete();

        verify(repository).deleteOthers(Set.of(69L, 70L));
    }
}
