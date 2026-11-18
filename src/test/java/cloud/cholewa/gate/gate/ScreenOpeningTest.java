package cloud.cholewa.gate.gate;

import cloud.cholewa.gate.config.GateProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScreenOpeningTest {

    private static final String SCREEN = "10010110001";
    //09:25:10 of the house
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-18T08:25:10Z"), ZoneId.of("Europe/Warsaw"));
    private static final LocalDateTime UNLOCKED = LocalDateTime.of(2026, 11, 18, 9, 25, 2);
    private static final GateProperties PROPERTIES = new GateProperties(List.of(SCREEN),
        new GateProperties.Open(Duration.ofSeconds(15)), new GateProperties.Screen(Duration.ofSeconds(60)));

    private final GateOpener opener = mock(GateOpener.class);
    private final ScreenOpening screenOpening = new ScreenOpening(opener, PROPERTIES, CLOCK);

    @BeforeEach
    void setUp() {
        when(opener.open(any(), any())).thenReturn(Mono.empty());
    }

    //the owner, 2026-11-17: every unlock from the indoor screen opens the gate, with or without a call
    @Test
    void should_open_the_gate_for_an_unlock_of_the_screen() {
        screenOpening.onUnlock("HOUSEHOLDER", SCREEN, UNLOCKED).as(StepVerifier::create).verifyComplete();

        verify(opener).open(GateOpener.Source.SCREEN, null);
    }

    //a card, a code, another source - stored by the router, never an opening
    @Test
    void should_open_nothing_for_another_unlock() {
        screenOpening.onUnlock("CARD", SCREEN, UNLOCKED).as(StepVerifier::create).verifyComplete();
        screenOpening.onUnlock("HOUSEHOLDER", "10010110002", UNLOCKED).as(StepVerifier::create).verifyComplete();
        screenOpening.onUnlock(null, null, UNLOCKED).as(StepVerifier::create).verifyComplete();

        verify(opener, never()).open(any(), any());
    }

    //it waited in the queue through a restart of the service: the gate would open minutes after the press
    @Test
    void should_open_nothing_for_an_unlock_older_than_the_limit() {
        screenOpening.onUnlock("HOUSEHOLDER", SCREEN, UNLOCKED.minusSeconds(61)).as(StepVerifier::create)
            .verifyComplete();

        verify(opener, never()).open(any(), any());
    }

    //the bridge publishes the attributes again after its restart: the same unlock never opens the gate twice
    @Test
    void should_open_the_gate_once_for_the_same_unlock_and_again_for_a_newer_one() {
        screenOpening.onUnlock("HOUSEHOLDER", SCREEN, UNLOCKED).as(StepVerifier::create).verifyComplete();
        screenOpening.onUnlock("HOUSEHOLDER", SCREEN, UNLOCKED).as(StepVerifier::create).verifyComplete();
        screenOpening.onUnlock("HOUSEHOLDER", SCREEN, UNLOCKED.minusSeconds(5)).as(StepVerifier::create)
            .verifyComplete();
        screenOpening.onUnlock("HOUSEHOLDER", SCREEN, UNLOCKED.plusSeconds(1)).as(StepVerifier::create)
            .verifyComplete();

        verify(opener, times(2)).open(GateOpener.Source.SCREEN, null);
    }

    //a refusal of the opener (too soon, no gate, the Shelly failing) is logged - the listener goes on
    @Test
    void should_complete_when_the_gate_does_not_open() {
        when(opener.open(any(), isNull())).thenReturn(Mono.error(new IllegalStateException("refused")));

        screenOpening.onUnlock("HOUSEHOLDER", SCREEN, UNLOCKED).as(StepVerifier::create).verifyComplete();
    }
}
