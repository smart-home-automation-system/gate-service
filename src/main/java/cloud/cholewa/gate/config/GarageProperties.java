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
 * The garage gates (HAS-473).
 *
 * @param gatewaySilentAfter how long the state of a sensor on a garage gate stands without its BTHome gateway confirming
 *                           it - by publishing the sensor or by answering the read of every five minutes - before the
 *                           gate is not known; a bare number is minutes. A Shelly BLU Door/Window sends only when it
 *                           changes (no beacon), so the silence of the sensor itself proves nothing; the gateway that
 *                           stopped answering does (the rule of the windows of blinds-service). At least two reads of
 *                           the gateway, pinned by a test
 */
@Validated
@ConfigurationProperties(prefix = "gate.garage")
public record GarageProperties(
    @NotNull @DurationMin(minutes = 10) @DurationMax(hours = 24) @DurationUnit(ChronoUnit.MINUTES)
    Duration gatewaySilentAfter
) {
}
