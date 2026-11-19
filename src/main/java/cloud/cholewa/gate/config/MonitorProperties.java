package cloud.cholewa.gate.config;

import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * The availability monitor (HAS-453).
 *
 * @param offlineAfter  how long the bridge may say offline, a Shelly of a gate go without answering or a garage gate stay
 *                      not known before the owner is told; a bare number is minutes
 * @param reminderEvery how often he is told again while something stays out
 */
@Validated
@ConfigurationProperties(prefix = "gate.monitor")
public record MonitorProperties(
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 1) @DurationMax(hours = 1) Duration offlineAfter,
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 10) @DurationMax(hours = 24) Duration reminderEvery
) {
}
