package cloud.cholewa.gate.garage;

import cloud.cholewa.gate.config.GarageAlarmProperties;
import cloud.cholewa.gate.database.GarageGateAlertEntity;
import cloud.cholewa.gate.garage.GarageAlarmEvaluator.Action;
import cloud.cholewa.gate.garage.GarageAlarmEvaluator.Closed;
import cloud.cholewa.gate.garage.GarageAlarmEvaluator.EndSilently;
import cloud.cholewa.gate.garage.GarageAlarmEvaluator.Raise;
import cloud.cholewa.gate.garage.GarageAlarmEvaluator.Remind;
import cloud.cholewa.gate.garage.WeatherClient.Temperature;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static cloud.cholewa.gate.garage.GarageAlarmEvaluator.COLD;
import static cloud.cholewa.gate.garage.GarageAlarmEvaluator.EMPTY_HOUSE;
import static cloud.cholewa.gate.garage.GarageAlarmEvaluator.LONG;
import static org.assertj.core.api.Assertions.assertThat;

//HAS-475 (owner 2026-11-18): cold - open 15 min below 15 C; empty house - open while nobody is at home for 5 min; long -
//open 30 min, not while an alarm holds; repeats every 30 min / 1 h, at most 10; closed ends them with one message
class GarageAlarmEvaluatorTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    private static final GarageAlarmProperties PROPERTIES = new GarageAlarmProperties(Duration.ofMinutes(15), 15.0,
        Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofMinutes(30), Duration.ofHours(1));
    private static final LocalDateTime OPENED = LocalDateTime.of(2026, 11, 18, 18, 0);
    private static final Optional<Temperature> COLD_OUTSIDE = Optional.of(new Temperature(7.5, false));
    private static final Optional<Temperature> WARM_OUTSIDE = Optional.of(new Temperature(18.0, false));
    private static final HouseOccupancy AT_HOME = new HouseOccupancy(HouseOccupancy.State.OCCUPIED, null);

    private static List<Action> decide(final GarageState state, final Optional<Temperature> outside,
                                       final HouseOccupancy house, final List<GarageGateAlertEntity> episodes,
                                       final int minutesOpen) {
        return GarageAlarmEvaluator.decide(new GarageSensors.GarageReading(state, OPENED, null), outside, house, episodes,
            OPENED.plusMinutes(minutesOpen), HOUSE, PROPERTIES);
    }

    private static GarageGateAlertEntity episode(final String kind, final int lastMessageMinutes, final int reminders) {
        return new GarageGateAlertEntity(1L, 148L, kind, OPENED, OPENED, OPENED.plusMinutes(lastMessageMinutes),
            reminders, null);
    }

    @Test
    void should_raise_the_cold_alarm_after_15_minutes_below_15_degrees() {
        assertThat(decide(GarageState.OPEN, COLD_OUTSIDE, AT_HOME, List.of(), 14)).isEmpty();
        assertThat(decide(GarageState.OPEN, COLD_OUTSIDE, AT_HOME, List.of(), 15)).containsExactly(new Raise(COLD, OPENED));
        //15 C is not below 15, a stale or missing reading is no cold
        assertThat(decide(GarageState.OPEN, Optional.of(new Temperature(15.0, false)), AT_HOME, List.of(), 20)).isEmpty();
        assertThat(decide(GarageState.OPEN, Optional.of(new Temperature(5.0, true)), AT_HOME, List.of(), 20)).isEmpty();
        assertThat(decide(GarageState.OPEN, Optional.empty(), AT_HOME, List.of(), 20)).isEmpty();
    }

    @Test
    void should_raise_the_empty_house_alarm_once_both_hold_for_5_minutes() {
        final HouseOccupancy emptySinceTen = new HouseOccupancy(HouseOccupancy.State.EMPTY, OPENED.plusMinutes(10));

        assertThat(decide(GarageState.OPEN, WARM_OUTSIDE, emptySinceTen, List.of(), 14)).isEmpty();
        assertThat(decide(GarageState.OPEN, WARM_OUTSIDE, emptySinceTen, List.of(), 15))
            .containsExactly(new Raise(EMPTY_HOUSE, OPENED));
        //a house not known, or empty since a time not known, is never empty
        assertThat(decide(GarageState.OPEN, WARM_OUTSIDE, HouseOccupancy.unknown(), List.of(), 20)).isEmpty();
        assertThat(decide(GarageState.OPEN, WARM_OUTSIDE, new HouseOccupancy(HouseOccupancy.State.EMPTY, null),
            List.of(), 20)).isEmpty();
    }

    @Test
    void should_warn_after_30_minutes_and_not_while_an_alarm_holds() {
        assertThat(decide(GarageState.OPEN, WARM_OUTSIDE, AT_HOME, List.of(), 30)).containsExactly(new Raise(LONG, OPENED));
        assertThat(decide(GarageState.OPEN, COLD_OUTSIDE, AT_HOME, List.of(episode(COLD, 15, 0)), 30)).isEmpty();
    }

    //an alarm every 30 min, the warning every hour, at most 10 repeats
    @Test
    void should_repeat_every_30_minutes_or_hour_at_most_10_times() {
        assertThat(decide(GarageState.OPEN, COLD_OUTSIDE, AT_HOME, List.of(episode(COLD, 15, 0)), 44)).isEmpty();
        assertThat(decide(GarageState.OPEN, COLD_OUTSIDE, AT_HOME, List.of(episode(COLD, 15, 0)), 45))
            .containsExactly(new Remind(episode(COLD, 15, 0)));
        assertThat(decide(GarageState.OPEN, COLD_OUTSIDE, AT_HOME, List.of(episode(COLD, 15, 10)), 600)).isEmpty();
        assertThat(decide(GarageState.OPEN, WARM_OUTSIDE, AT_HOME, List.of(episode(LONG, 30, 0)), 89)).isEmpty();
        assertThat(decide(GarageState.OPEN, WARM_OUTSIDE, AT_HOME, List.of(episode(LONG, 30, 0)), 90))
            .containsExactly(new Remind(episode(LONG, 30, 0)));
    }

    //the gate still open, the condition gone (warmer, somebody came home): the episode ends without a word
    @Test
    void should_end_an_episode_whose_condition_is_gone_without_a_word() {
        assertThat(decide(GarageState.OPEN, WARM_OUTSIDE, AT_HOME, List.of(episode(COLD, 15, 0)), 20))
            .containsExactly(new EndSilently(episode(COLD, 15, 0)));
    }

    @Test
    void should_tell_the_gate_closed_once_for_every_open_episode() {
        final List<GarageGateAlertEntity> open = List.of(episode(COLD, 15, 0), episode(LONG, 30, 0));

        assertThat(decide(GarageState.CLOSED, COLD_OUTSIDE, AT_HOME, open, 40)).containsExactly(new Closed(open));
        assertThat(decide(GarageState.CLOSED, COLD_OUTSIDE, AT_HOME, List.of(), 40)).isEmpty();
    }

    //a gate not known never alarms nor ends anything
    @Test
    void should_do_nothing_while_the_gate_is_not_known() {
        assertThat(decide(GarageState.UNKNOWN, COLD_OUTSIDE, AT_HOME, List.of(episode(COLD, 15, 0)), 120)).isEmpty();
    }

    //closed and opened again between two passes: the episode of the earlier opening is told closed
    @Test
    void should_close_the_episodes_of_an_earlier_opening() {
        final GarageGateAlertEntity earlier = new GarageGateAlertEntity(1L, 148L, COLD, OPENED.minusHours(1),
            OPENED.minusMinutes(45), OPENED.minusMinutes(45), 0, null);

        assertThat(decide(GarageState.OPEN, COLD_OUTSIDE, AT_HOME, List.of(earlier), 1))
            .containsExactly(new Closed(List.of(earlier)));
    }
}
