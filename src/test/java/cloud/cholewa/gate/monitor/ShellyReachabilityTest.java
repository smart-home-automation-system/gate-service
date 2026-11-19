package cloud.cholewa.gate.monitor;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.cholewa.gate.monitor.ShellyReachability.Reach;
import cloud.cholewa.gate.monitor.ShellyReachability.Target;
import cloud.cholewa.shelly.http.ShellyGen1Client;
import cloud.cholewa.shelly.model.Gen1DeviceStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

//HAS-453: a Shelly of a gate unreachable since the first pass it did not answer, reachable with the first that it does;
//every device with its own failure; the first failure of an episode a WARN - what the alerts of the log see
class ShellyReachabilityTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    private static final Instant START = Instant.parse("2026-11-19T09:00:00Z");
    private static final Target KATE = new Target(148, "the garage gate Kate (148)", "192.0.2.79");
    private static final Target KRIS = new Target(149, "the garage gate Kris (149)", "192.0.2.29");

    private final ShellyGen1Client shelly = mock(ShellyGen1Client.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(START);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private final ShellyReachability reachability = new ShellyReachability(shelly, new Clock() {
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
        logged.start();
        ((Logger) LoggerFactory.getLogger(ShellyReachability.class)).addAppender(logged);
        when(shelly.status("192.0.2.29")).thenReturn(Mono.just(new Gen1DeviceStatus()));
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(ShellyReachability.class)).detachAppender(logged);
    }

    private void pass(final long secondsAfterStart) {
        now.set(START.plusSeconds(secondsAfterStart));
        reachability.poll(List.of(KATE, KRIS)).as(StepVerifier::create).verifyComplete();
    }

    @Test
    void should_keep_since_when_a_shelly_does_not_answer_and_warn_once() {
        assertThat(reachability.of(148)).isEmpty();
        when(shelly.status("192.0.2.79")).thenReturn(Mono.error(new IllegalStateException("connection refused")));

        pass(0);
        pass(60);

        final LocalDateTime first = LocalDateTime.ofInstant(START, HOUSE);
        assertThat(reachability.of(148)).contains(new Reach(first));
        assertThat(reachability.of(149)).contains(new Reach(null));
        assertThat(logged.list).hasSize(1);
        assertThat(logged.list.getFirst().getLevel()).isEqualTo(Level.WARN);
        assertThat(logged.list.getFirst().getFormattedMessage()).contains("Kate").contains("connection refused");
    }

    @Test
    void should_be_reachable_again_with_the_first_answer() {
        when(shelly.status("192.0.2.79")).thenReturn(Mono.error(new IllegalStateException("timeout")));
        pass(0);
        when(shelly.status("192.0.2.79")).thenReturn(Mono.just(new Gen1DeviceStatus()));
        pass(60);

        assertThat(reachability.of(148)).contains(new Reach(null));
        assertThat(logged.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.WARN, Level.INFO);
    }

    //a slow Shelly never holds the others: each call is bounded
    @Test
    void should_not_wait_for_a_shelly_that_never_answers() {
        when(shelly.status("192.0.2.79")).thenReturn(Mono.never());

        StepVerifier.withVirtualTime(() -> reachability.poll(List.of(KATE, KRIS)))
            .thenAwait(ShellyReachability.CALL_TIMEOUT)
            .verifyComplete();

        assertThat(reachability.of(148)).map(Reach::unreachableSince).isPresent();
        assertThat(reachability.of(149)).contains(new Reach(null));
    }

    @Test
    void should_forget_a_gate_no_longer_listed() {
        when(shelly.status("192.0.2.79")).thenReturn(Mono.just(new Gen1DeviceStatus()));
        pass(0);
        reachability.poll(List.of(KRIS)).as(StepVerifier::create).verifyComplete();

        assertThat(reachability.of(148)).isEmpty();
    }
}
