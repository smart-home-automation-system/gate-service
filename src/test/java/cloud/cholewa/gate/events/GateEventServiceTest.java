package cloud.cholewa.gate.events;

import cloud.cholewa.gate.api.model.GateEvent;
import cloud.cholewa.gate.database.GateCommandEntity;
import cloud.cholewa.gate.database.GateCommandRepository;
import cloud.cholewa.gate.database.IntercomEventEntity;
import cloud.cholewa.gate.database.IntercomEventRepository;
import cloud.cholewa.gate.error.GateException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//HAS-450: the timeline of the door station and of the gate, as the house lived it on 2026-11-18
class GateEventServiceTest {

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 11, 18, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 11, 19, 0, 0);
    private static final LocalDateTime RING = LocalDateTime.of(2026, 11, 18, 8, 22, 56);
    private static final LocalDateTime OPENED = LocalDateTime.of(2026, 11, 18, 10, 56, 9);

    private final IntercomEventRepository intercomEvents = mock(IntercomEventRepository.class);
    private final GateCommandRepository commands = mock(GateCommandRepository.class);
    private final GateEventService service = new GateEventService(intercomEvents, commands);

    //both tables come newest first; the timeline keeps that order across them
    @Test
    void should_merge_the_events_and_the_commands_newest_first() {
        when(intercomEvents.findInPeriod(FROM, TO)).thenReturn(Flux.just(
            IntercomEventEntity.unlock(OPENED, OPENED, "HOUSEHOLDER", "494848494849494848484900", "10010110001", 1),
            IntercomEventEntity.dismissed(RING.plusMinutes(1)),
            IntercomEventEntity.ring(RING, "10010100000")));
        when(commands.findInPeriod(FROM, TO)).thenReturn(Flux.just(
            GateCommandEntity.of(OPENED.plusSeconds(5), "DASHBOARD", "Anna", GateCommandEntity.TOO_SOON),
            GateCommandEntity.of(RING.plusMinutes(3), "SCREEN", null, GateCommandEntity.FAILED)));

        service.events(FROM, TO).collectList().as(StepVerifier::create)
            .assertNext(events -> assertThat(events).containsExactly(
                new GateEvent("command", OPENED.plusSeconds(5), null, null, null, null, "dashboard", "Anna", "too soon"),
                new GateEvent("unlock", OPENED, null, 1, "HOUSEHOLDER", "10010110001", null, null, null),
                new GateEvent("command", RING.plusMinutes(3), null, null, null, null, "screen", null, "failed"),
                new GateEvent("dismissed", RING.plusMinutes(1), null, null, null, null, null, null, null),
                new GateEvent("ring", RING, "10010100000", null, null, null, null, null, null)))
            .verifyComplete();
    }

    //a kind written by a later release is left out, the rest served
    @Test
    void should_leave_out_an_event_of_a_kind_it_does_not_know() {
        when(intercomEvents.findInPeriod(FROM, TO)).thenReturn(Flux.just(
            new IntercomEventEntity(7L, "ANSWERED", OPENED, OPENED, null, null, null, null, null),
            IntercomEventEntity.ring(RING, "10010100000")));
        when(commands.findInPeriod(FROM, TO)).thenReturn(Flux.empty());

        service.events(FROM, TO).map(GateEvent::kind).collectList().as(StepVerifier::create)
            .assertNext(kinds -> assertThat(kinds).containsExactly("ring"))
            .verifyComplete();
    }

    @Test
    void should_answer_an_empty_period_with_no_events() {
        when(intercomEvents.findInPeriod(FROM, TO)).thenReturn(Flux.empty());
        when(commands.findInPeriod(FROM, TO)).thenReturn(Flux.empty());

        service.events(FROM, TO).as(StepVerifier::create).verifyComplete();
    }

    //31 days is the most; to not before from - neither reaches the database
    @Test
    void should_refuse_a_period_backwards_empty_or_longer_than_31_days() {
        service.events(TO, FROM).as(StepVerifier::create).verifyError(GateException.InvalidRange.class);
        service.events(FROM, FROM).as(StepVerifier::create).verifyError(GateException.InvalidRange.class);
        service.events(FROM, FROM.plusDays(31).plusSeconds(1)).as(StepVerifier::create)
            .verifyError(GateException.InvalidRange.class);

        verify(intercomEvents, never()).findInPeriod(any(), any());
        verify(commands, never()).findInPeriod(any(), any());
    }

    @Test
    void should_take_a_period_of_31_days() {
        when(intercomEvents.findInPeriod(any(), any())).thenReturn(Flux.empty());
        when(commands.findInPeriod(any(), any())).thenReturn(Flux.empty());

        service.events(FROM, FROM.plusDays(31)).as(StepVerifier::create).verifyComplete();
    }
}
