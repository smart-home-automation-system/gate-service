package cloud.cholewa.gate.garage;

import cloud.cholewa.gate.config.GarageAlarmProperties;
import cloud.cholewa.gate.database.GarageGateAlertEntity;
import cloud.cholewa.gate.garage.WeatherClient.Temperature;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

//What one pass does about one garage gate (HAS-475) - pure, so every rule is tested on a clock:
//- a gate not known never alarms and keeps its episodes as they are;
//- a gate closed ends every open episode of it with one "closed";
//- a gate open: COLD while it stands open coldOpenFor and the temperature outside (not stale) is below coldBelow;
//  EMPTY_HOUSE while nobody is at home and both hold emptyFor; LONG while it stands open longAfter - never raised nor
//  repeated while an alarm of that gate holds. An episode that holds is repeated every alarmEvery / warningEvery, at
//  most 10 times; one whose condition no longer holds while the gate stays open ends without a word;
//- an episode of an earlier opening (the gate closed and opened again between two passes) ends with one "closed"
public final class GarageAlarmEvaluator {

    public static final String COLD = "COLD";
    public static final String EMPTY_HOUSE = "EMPTY_HOUSE";
    public static final String LONG = "LONG";
    static final int MAX_REMINDERS = 10;

    private GarageAlarmEvaluator() {
    }

    /** What a pass does: tell a new episode, repeat one, end one without a word, or tell the gate closed. */
    public sealed interface Action {
    }

    public record Raise(String kind, LocalDateTime openSince) implements Action {
    }

    public record Remind(GarageGateAlertEntity episode) implements Action {
    }

    public record EndSilently(GarageGateAlertEntity episode) implements Action {
    }

    public record Closed(List<GarageGateAlertEntity> episodes) implements Action {

        public Closed {
            episodes = List.copyOf(episodes);
        }
    }

    public static List<Action> decide(
        final GarageSensors.GarageReading reading,
        final Optional<Temperature> temperature,
        final HouseOccupancy house,
        final List<GarageGateAlertEntity> episodes,
        final LocalDateTime now,
        final ZoneId zone,
        final GarageAlarmProperties properties
    ) {
        if (reading.state() == GarageState.UNKNOWN) {
            return List.of();
        }
        if (reading.state() == GarageState.CLOSED || reading.since() == null) {
            return episodes.isEmpty() || reading.state() != GarageState.CLOSED ? List.of() : List.of(new Closed(episodes));
        }

        final LocalDateTime openSince = reading.since();
        final List<Action> actions = new ArrayList<>();
        final List<GarageGateAlertEntity> earlier = episodes.stream()
            .filter(episode -> !openSince.equals(episode.openSince()))
            .toList();
        if (!earlier.isEmpty()) {
            actions.add(new Closed(earlier));
        }
        final List<GarageGateAlertEntity> current = episodes.stream()
            .filter(episode -> openSince.equals(episode.openSince()))
            .toList();

        final Duration open = between(openSince, now, zone);
        final boolean cold = !open.minus(properties.coldOpenFor()).isNegative()
            && temperature.filter(outside -> !outside.stale()).map(Temperature::degrees)
            .filter(degrees -> degrees < properties.coldBelow()).isPresent();
        final boolean empty = house.empty() && house.emptySince() != null
            && !between(later(openSince, house.emptySince()), now, zone).minus(properties.emptyFor()).isNegative();
        final boolean longOpen = !open.minus(properties.longAfter()).isNegative();

        alarm(actions, COLD, cold, of(current, COLD), openSince, now, zone, properties.alarmEvery());
        alarm(actions, EMPTY_HOUSE, empty, of(current, EMPTY_HOUSE), openSince, now, zone, properties.alarmEvery());
        final Optional<GarageGateAlertEntity> warning = of(current, LONG);
        if (cold || empty) {
            //the warning waits while an alarm holds: kept, not repeated
            return actions;
        }
        alarm(actions, LONG, longOpen, warning, openSince, now, zone, properties.warningEvery());
        return actions;
    }

    private static void alarm(final List<Action> actions, final String kind, final boolean holds,
                              final Optional<GarageGateAlertEntity> episode, final LocalDateTime openSince,
                              final LocalDateTime now, final ZoneId zone, final Duration every) {
        if (holds && episode.isEmpty()) {
            actions.add(new Raise(kind, openSince));
            return;
        }
        episode.ifPresent(held -> {
            if (!holds) {
                actions.add(new EndSilently(held));
            } else if (held.reminders() < MAX_REMINDERS
                && !between(held.lastMessageAt(), now, zone).minus(every).isNegative()) {
                actions.add(new Remind(held));
            }
        });
    }

    private static Optional<GarageGateAlertEntity> of(final List<GarageGateAlertEntity> episodes, final String kind) {
        return episodes.stream().filter(episode -> Objects.equals(kind, episode.kind())).findFirst();
    }

    private static LocalDateTime later(final LocalDateTime one, final LocalDateTime other) {
        return one.isAfter(other) ? one : other;
    }

    //two times of the house compared as instants of its zone, so the night the clocks change counts the hour right
    private static Duration between(final LocalDateTime from, final LocalDateTime to, final ZoneId zone) {
        return Duration.between(from.atZone(zone), to.atZone(zone));
    }
}
