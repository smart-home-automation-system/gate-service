package cloud.cholewa.gate.monitor;

import cloud.cholewa.gate.monitor.AvailabilityEvaluator.Alert;
import cloud.cholewa.gate.monitor.AvailabilityEvaluator.Decision;
import cloud.cholewa.gate.monitor.AvailabilityEvaluator.Watched;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

//HAS-453: out for its own bound is an alert, told and still out a reminder every hour, back an info - an alert that never
//got through too; not known is never back; a thing no longer watched is forgotten
class AvailabilityEvaluatorTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 19, 10, 0);
    private static final Duration FIVE = Duration.ofMinutes(5);
    private static final Duration HOUR = Duration.ofHours(1);

    private static Watched out(final String item, final long minutes) {
        return new Watched(item, item, NOW.minusMinutes(minutes), true, FIVE);
    }

    private static Watched fine(final String item) {
        return new Watched(item, item, null, true, FIVE);
    }

    private static Decision decide(final List<Watched> watched, final Alert... open) {
        return AvailabilityEvaluator.decide(watched, Arrays.stream(open)
            .collect(Collectors.toMap(Alert::item, Function.identity())), NOW, HOUR);
    }

    @Test
    void should_raise_an_alert_once_out_for_its_bound_and_not_before() {
        assertThat(decide(List.of(out("bridge", 4))).alerts()).isEmpty();

        final Decision decision = decide(List.of(out("bridge", 6)));
        assertThat(decision.alerts()).containsExactly(new Alert("bridge", "bridge", NOW.minusMinutes(6), false, null));
    }

    //a relay that does not switch itself off is told at once
    @Test
    void should_raise_a_thing_without_a_bound_at_once() {
        final Watched relay = new Watched("auto-off:148", "relay", NOW, true, Duration.ZERO);

        assertThat(decide(List.of(relay)).alerts()).extracting(Alert::item).containsExactly("auto-off:148");
    }

    @Test
    void should_remind_a_told_alert_every_hour_and_try_an_untold_one_again() {
        final Alert told = new Alert("shelly:148", "Kate", NOW.minusMinutes(70), true, NOW.minusMinutes(61));
        final Alert toldLately = new Alert("shelly:149", "Kris", NOW.minusMinutes(70), true, NOW.minusMinutes(30));
        final Alert untold = new Alert("bridge", "bridge", NOW.minusMinutes(10), false, null);

        final Decision decision = decide(List.of(out("shelly:148", 70), out("shelly:149", 70), out("bridge", 10)),
            told, toldLately, untold);

        assertThat(decision.reminders()).containsExactly(told);
        assertThat(decision.alerts()).containsExactly(untold);
    }

    //an alert that never got through is announced with the return all the same
    @Test
    void should_announce_every_return_told_or_not_but_never_a_thing_not_known() {
        final Alert told = new Alert("shelly:148", "Kate", NOW.minusMinutes(20), true, NOW.minusMinutes(14));
        final Alert untold = new Alert("bridge", "bridge", NOW.minusMinutes(10), false, null);
        final Alert notKnown = new Alert("shelly:149", "Kris", NOW.minusMinutes(20), true, NOW.minusMinutes(14));

        final Decision decision = decide(List.of(fine("shelly:148"), fine("bridge"),
            new Watched("shelly:149", "Kris", null, false, FIVE)), told, untold, notKnown);

        assertThat(decision.returns()).containsExactlyInAnyOrder(told, untold);
        assertThat(decision.alerts()).isEmpty();
        assertThat(decision.forgotten()).isEmpty();
    }

    @Test
    void should_forget_an_alert_of_a_thing_no_longer_watched() {
        final Alert removed = new Alert("shelly:150", "Old", NOW.minusHours(2), true, NOW.minusHours(1));

        assertThat(decide(List.of(fine("bridge")), removed).forgotten()).containsExactly(removed);
    }
}
