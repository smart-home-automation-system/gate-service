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
 * The retention of the log of the gate (HAS-450): the events of the door station and the commands of the gate older
 * than {@code gate.retention} are deleted every night. It decides what is deleted, so it carries its unit (days - a
 * bare number is never milliseconds) and its bounds, pinned by a test; at least the 31 days the events API serves.
 */
@Validated
@ConfigurationProperties("gate")
public record GateRetentionProperties(
    @NotNull @DurationUnit(ChronoUnit.DAYS) @DurationMin(days = 31) @DurationMax(days = 3650) Duration retention
) {
}
