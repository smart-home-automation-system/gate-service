package cloud.cholewa.gate.events;

import cloud.cholewa.gate.config.GateRetentionProperties;
import cloud.cholewa.gate.database.GarageGateChangeRepository;
import cloud.cholewa.gate.database.GateCommandRepository;
import cloud.cholewa.gate.database.IntercomEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//HAS-450, the nightly retention of the events of the door station and of the commands of the gate
class GateRetentionTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    //03:40 of the house on 18 November
    private static final Instant NIGHT = Instant.parse("2026-11-18T02:40:00Z");
    private static final LocalDateTime BOUND = LocalDateTime.of(2026, 8, 20, 3, 40);

    private final IntercomEventRepository intercomEvents = mock(IntercomEventRepository.class);
    private final GateCommandRepository commands = mock(GateCommandRepository.class);
    private final GarageGateChangeRepository garageChanges = mock(GarageGateChangeRepository.class);

    {
        //HAS-474: the changes of the garage gates go the same way, after the commands
        when(garageChanges.deleteBefore(any())).thenReturn(Mono.just(3L));
    }
    private final AtomicReference<Instant> now = new AtomicReference<>(NIGHT);
    private final GateRetention sut = new GateRetention(intercomEvents, commands, garageChanges,
        new GateRetentionProperties(Duration.ofDays(90)), new Clock() {
            @Override
            public ZoneId getZone() {
                return HOUSE;
            }

            @Override
            public Clock withZone(final ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        });

    @BeforeEach
    void setUp() {
        when(intercomEvents.deleteBefore(any())).thenReturn(Mono.just(40L));
        when(commands.deleteBefore(any())).thenReturn(Mono.just(12L));
    }

    @Test
    void should_delete_both_tables_before_the_retention() {
        sut.purge().as(StepVerifier::create).verifyComplete();

        verify(intercomEvents).deleteBefore(BOUND);
        verify(commands).deleteBefore(BOUND);
    }

    //each table on its own: a failed delete of one never keeps the other, and the pass never fails
    @Test
    void should_delete_the_commands_when_the_events_fail() {
        when(intercomEvents.deleteBefore(any())).thenReturn(Mono.error(new IllegalStateException("database down")));

        sut.purge().as(StepVerifier::create).verifyComplete();

        verify(commands).deleteBefore(BOUND);
    }

    @Test
    void should_complete_when_both_deletes_fail() {
        when(intercomEvents.deleteBefore(any())).thenReturn(Mono.error(new IllegalStateException("database down")));
        when(commands.deleteBefore(any())).thenReturn(Mono.error(new IllegalStateException("database down")));

        sut.purge().as(StepVerifier::create).verifyComplete();
    }

    //Spring subscribes again to the same Mono every night: the bound is read at every pass, not at the start of the pod
    @Test
    void should_move_the_bound_with_every_night() {
        final Mono<Void> pass = sut.purge();

        pass.as(StepVerifier::create).verifyComplete();
        now.set(NIGHT.plus(Duration.ofDays(1)));
        pass.as(StepVerifier::create).verifyComplete();

        verify(intercomEvents).deleteBefore(BOUND);
        verify(intercomEvents).deleteBefore(BOUND.plusDays(1));
        verify(commands).deleteBefore(BOUND.plusDays(1));
    }

    @Test
    void should_end_a_pass_that_never_completes_at_its_bound() {
        when(intercomEvents.deleteBefore(any())).thenReturn(Mono.never());

        StepVerifier.withVirtualTime(sut::purge)
            .thenAwait(GateRetention.LIMIT.plusSeconds(1))
            .verifyComplete();
    }

    @Test
    void should_run_at_03_40_every_night() throws NoSuchMethodException {
        final Method purge = GateRetention.class.getDeclaredMethod("purge");

        assertThat(purge.getAnnotation(Scheduled.class).cron()).isEqualTo("0 40 3 * * *");
    }
}
