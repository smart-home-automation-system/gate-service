package cloud.cholewa.gate.monitor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

//What the availability monitor makes of what it watches and the alerts already written (HAS-453) - pure, pinned by
//AvailabilityEvaluatorTest, by the rules of the monitors of aircon-service and light-service. A thing is out once it has
//been out for its own bound (the offline-after of the monitor, none for a relay that does not switch itself off). Out
//and not told - no row, or a row whose alert never got through - it is an alert; told and still out, a reminder every
//reminderEvery; out before (told or not) and fine now, an info - never a thing only not known after a restart, which is not
//back until it is known. A row of a thing no longer watched (a gate removed from the registry) is forgotten without a word
public final class AvailabilityEvaluator {

    private AvailabilityEvaluator() {
    }

    /**
     * A watched thing: its key and how the messages name it, since when it is out (null while it is fine or not known),
     * whether its state is known, and how long it may be out before the owner is told.
     */
    public record Watched(String item, String name, LocalDateTime since, boolean known, Duration after) {
    }

    /** An alert written: {@code told} once the broker took it, {@code lastAlertAt} the alert or the last reminder told. */
    public record Alert(String item, String name, LocalDateTime since, boolean told, LocalDateTime lastAlertAt) {
    }

    /** What is due: alerts to tell, reminders, returns, and the alerts of things no longer watched. */
    public record Decision(List<Alert> alerts, List<Alert> reminders, List<Alert> returns, List<Alert> forgotten) {
    }

    public static Decision decide(final List<Watched> watched, final Map<String, Alert> open, final LocalDateTime now,
                                  final Duration reminderEvery) {
        final List<Alert> alerts = watched.stream()
            .filter(thing -> out(thing, now))
            .filter(thing -> Optional.ofNullable(open.get(thing.item())).map(alert -> !alert.told()).orElse(true))
            .map(thing -> Optional.ofNullable(open.get(thing.item()))
                .map(alert -> new Alert(alert.item(), alert.name(), alert.since(), false, alert.lastAlertAt()))
                .orElseGet(() -> new Alert(thing.item(), thing.name(), thing.since(), false, null)))
            .toList();
        final List<Alert> reminders = watched.stream()
            .filter(thing -> out(thing, now))
            .flatMap(thing -> Optional.ofNullable(open.get(thing.item())).stream())
            .filter(Alert::told)
            .filter(alert -> alert.lastAlertAt() == null || !alert.lastAlertAt().plus(reminderEvery).isAfter(now))
            .toList();
        final List<Alert> returns = watched.stream()
            .filter(thing -> thing.known() && thing.since() == null)
            .flatMap(thing -> Optional.ofNullable(open.get(thing.item())).stream())
            .toList();
        final Set<String> items = watched.stream().map(Watched::item).collect(Collectors.toSet());
        final List<Alert> forgotten = open.values().stream().filter(alert -> !items.contains(alert.item())).toList();
        return new Decision(alerts, reminders, returns, forgotten);
    }

    private static boolean out(final Watched thing, final LocalDateTime now) {
        return thing.since() != null && !thing.since().plus(thing.after()).isAfter(now);
    }
}
