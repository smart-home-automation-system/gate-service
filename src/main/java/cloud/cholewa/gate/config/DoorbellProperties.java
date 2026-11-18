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
 * The ring told to the household (HAS-452): {@code ignore-repeat-for} - no second {@code doorbell ringing} within it of
 * the last one told, so a visitor pressing the button again during one call is one message (a call of the station
 * lasts 60 s, the bridge says idle after it). It decides whether the household hears a ring, so it carries its unit and
 * bounds, pinned by a test.
 */
@Validated
@ConfigurationProperties("gate.doorbell")
public record DoorbellProperties(
    @NotNull @DurationUnit(ChronoUnit.SECONDS) @DurationMin(seconds = 10) @DurationMax(minutes = 10)
    Duration ignoreRepeatFor
) {
}
