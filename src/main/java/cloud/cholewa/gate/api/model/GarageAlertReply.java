package cloud.cholewa.gate.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * An open alarm or warning of a garage gate (HAS-454, from the episodes of HAS-475): the registry id of the gate and its
 * name (left out when the registry no longer knows the gate), {@code kind} {@code cold}, {@code empty house} or
 * {@code long}, {@code level} {@code alarm} (cold, empty house) or {@code warning} (long), since when the gate stands open,
 * when the household was first told and how many repeats followed (at most 10). The fields are wire contract for the
 * dashboard.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GarageAlertReply(
    long gate,
    String name,
    String kind,
    String level,
    LocalDateTime openSince,
    LocalDateTime raisedAt,
    int reminders
) {

    public static final String ALARM = "alarm";
    public static final String WARNING = "warning";
}
