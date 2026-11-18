package cloud.cholewa.gate.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * The alarms of a garage gate left open (HAS-475, owner 2026-11-18). Every duration decides when the household is told,
 * so each carries its unit - a bare number is minutes - and its bounds, pinned by a test.
 *
 * @param coldOpenFor  how long the gate stands open before the cold alarm (PT15M)
 * @param coldBelow    the temperature outside, in degrees C, below which an open gate is the cold alarm (15)
 * @param emptyFor     how long the gate stands open while nobody is at home before the empty-house alarm (PT5M)
 * @param longAfter    how long the gate stands open before the warning, whatever the weather (PT30M)
 * @param alarmEvery   the repeat of an alarm while it holds (PT30M)
 * @param warningEvery the repeat of the warning while it holds (PT1H)
 */
@Validated
@ConfigurationProperties(prefix = "gate.garage.alarm")
public record GarageAlarmProperties(
    @NotNull @DurationMin(minutes = 1) @DurationMax(hours = 6) @DurationUnit(ChronoUnit.MINUTES)
    Duration coldOpenFor,
    @NotNull @DecimalMin("-30") @DecimalMax("30")
    Double coldBelow,
    @NotNull @DurationMin(minutes = 1) @DurationMax(hours = 6) @DurationUnit(ChronoUnit.MINUTES)
    Duration emptyFor,
    @NotNull @DurationMin(minutes = 5) @DurationMax(hours = 24) @DurationUnit(ChronoUnit.MINUTES)
    Duration longAfter,
    @NotNull @DurationMin(minutes = 5) @DurationMax(hours = 24) @DurationUnit(ChronoUnit.MINUTES)
    Duration alarmEvery,
    @NotNull @DurationMin(minutes = 5) @DurationMax(hours = 24) @DurationUnit(ChronoUnit.MINUTES)
    Duration warningEvery
) {
}
