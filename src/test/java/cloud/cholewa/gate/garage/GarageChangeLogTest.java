package cloud.cholewa.gate.garage;

import cloud.cholewa.gate.database.GarageGateChangeEntity;
import cloud.cholewa.gate.database.GarageGateChangeRepository;
import cloud.cholewa.gate.gate.GarageGate;
import cloud.cholewa.gate.gate.GateRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//HAS-474: a change of a sensor is a row for every garage gate that names it, in the time of the house, once it held for
//SETTLE - the bounce of the sensor at the ends of the travel (Kate, 2026-11-19) is one change or none; never a failure
class GarageChangeLogTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    private static final Instant START = Instant.parse("2026-11-19T07:26:54Z");

    private final GateRegistry gateRegistry = mock(GateRegistry.class);
    private final GarageGateChangeRepository repository = mock(GarageGateChangeRepository.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(START);
    private final GarageChangeLog log = new GarageChangeLog(gateRegistry, repository, new Clock() {
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

    {
        when(gateRegistry.garages()).thenReturn(List.of(
            new GateRegistry.GarageGateState(new GarageGate(148, "Kate", "192.0.2.79", 0, List.of(69L)), 1.0, true, null),
            new GateRegistry.GarageGateState(new GarageGate(149, "Kris", "192.0.2.29", 0, List.of(70L)), 1.0, true, null)));
        doAnswer(saved -> Mono.just(saved.getArgument(0))).when(repository).save(any());
    }

    private void flip(final long sensor, final boolean open, final long secondsAfterStart, final Instant openedHeard) {
        now.set(START.plusSeconds(secondsAfterStart));
        log.logChange(sensor, open, START.plusSeconds(secondsAfterStart), openedHeard)
            .as(StepVerifier::create).verifyComplete();
    }

    private void writeAt(final long secondsAfterStart) {
        now.set(START.plusSeconds(secondsAfterStart));
        log.writeSettledOnce().as(StepVerifier::create).verifyComplete();
    }

    private static LocalDateTime house(final long secondsAfterStart) {
        return LocalDateTime.ofInstant(START.plusSeconds(secondsAfterStart), HOUSE);
    }

    //the opening of 2026-11-19 08:26: open, closed, open within 2 s - one opening, at the last flip
    @Test
    void should_write_a_bounced_opening_once_it_settled() {
        flip(69L, true, 0, null);
        flip(69L, false, 1, START);
        flip(69L, true, 2, null);

        writeAt(6);
        verify(repository, never()).save(any());

        writeAt(7);
        verify(repository).save(GarageGateChangeEntity.of(148, house(2), true, null));
        writeAt(20);
        verify(repository, times(1)).save(any());
    }

    //the closing of 08:28: closed, open, closed - one closing, since the opening before the bounce
    @Test
    void should_write_a_bounced_closing_with_since_when_the_gate_stood_open_before_it() {
        flip(69L, false, 76, START.plusSeconds(2));
        flip(69L, true, 77, null);
        flip(69L, false, 78, START.plusSeconds(77));

        writeAt(83);
        verify(repository).save(GarageGateChangeEntity.of(148, house(78), false, house(2)));
    }

    @Test
    void should_write_nothing_for_a_bounce_that_ends_where_it_began() {
        flip(70L, true, 0, null);
        flip(70L, false, 1, START);

        writeAt(10);
        verify(repository, never()).save(any());
    }

    //a time the gateway did not give is now
    @Test
    void should_write_a_change_at_now_when_the_gateway_gave_no_time() {
        now.set(START);
        log.logChange(70L, true, null, null).as(StepVerifier::create).verifyComplete();

        writeAt(5);
        verify(repository).save(GarageGateChangeEntity.of(149, house(0), true, null));
    }

    //a pass the bound cuts off puts back the change it was writing - the next pass writes it
    @Test
    void should_write_a_change_the_bound_cut_off_with_the_next_pass() {
        doReturn(Mono.never()).when(repository).save(any());
        flip(69L, true, 0, null);

        now.set(START.plusSeconds(5));
        log.writeSettledOnce().subscribe().dispose();

        doReturn(Mono.just(GarageGateChangeEntity.of(148, house(0), true, null))).when(repository).save(any());
        writeAt(10);
        verify(repository, times(2)).save(GarageGateChangeEntity.of(148, house(0), true, null));
        writeAt(20);
        verify(repository, times(2)).save(any());
    }

    //the chain a cut pass was writing is put back and written again, the one it did not reach stays where it was
    @Test
    void should_leave_the_chains_a_cut_pass_did_not_reach() {
        doReturn(Mono.never()).when(repository).save(any());
        flip(69L, true, 0, null);
        flip(70L, true, 0, null);

        now.set(START.plusSeconds(5));
        log.writeSettledOnce().subscribe().dispose();
        verify(repository, times(1)).save(any());

        doAnswer(saved -> Mono.just(saved.getArgument(0))).when(repository).save(any());
        writeAt(10);
        //whichever of the two the pass took first: three saves, each gate written
        verify(repository, times(3)).save(any());
        verify(repository, atLeastOnce()).save(GarageGateChangeEntity.of(148, house(0), true, null));
        verify(repository, atLeastOnce()).save(GarageGateChangeEntity.of(149, house(0), true, null));
    }

    //a flip that replaced a settled chain before its turn keeps the new chain: open, then closed while the pass writes
    //the other sensor - a bounce back, nothing written for it; whichever of the two the pass takes first
    @Test
    void should_keep_a_chain_a_flip_replaced_while_the_pass_ran() {
        flip(69L, true, 0, null);
        flip(70L, true, 0, null);
        doAnswer(saved -> {
            final GarageGateChangeEntity row = saved.getArgument(0);
            log.logChange(row.gateId() == 148 ? 70L : 69L, false, START.plusSeconds(5), START).block();
            return Mono.just(saved.getArgument(0));
        }).when(repository).save(any());

        writeAt(5);
        verify(repository, times(1)).save(any());

        doAnswer(saved -> Mono.just(saved.getArgument(0))).when(repository).save(any());
        writeAt(11);
        verify(repository, times(1)).save(any());
    }

    //a flip after its chain was written starts a chain of its own, judged from its own value
    @Test
    void should_start_a_new_chain_after_one_was_written() {
        flip(69L, true, 0, null);
        writeAt(5);
        flip(69L, false, 30, START);
        writeAt(35);

        verify(repository).save(GarageGateChangeEntity.of(148, house(30), false, house(0)));
    }

    @Test
    void should_write_nothing_for_a_sensor_no_gate_names_and_never_fail() {
        flip(61L, true, 0, null);
        writeAt(5);
        verify(repository, never()).save(any());

        doReturn(Mono.error(new IllegalStateException("database down"))).when(repository).save(any());
        flip(69L, true, 10, null);
        writeAt(15);
    }
}
