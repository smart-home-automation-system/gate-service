package cloud.cholewa.gate.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * The opening of the gate (HAS-449). {@code screens} - the numbers of the indoor screens whose unlock opens the gate
 * (the {@code number} of an unlock of the station: {@code 10010110001}); {@code open.ignore-repeat-for} - a second
 * opening within it is refused (the gate is step-by-step: a second pulse while it moves stops or reverses it);
 * {@code screen.max-age} - an unlock of a screen older than it opens nothing (it waited in the queue through a restart
 * of the service). Both durations decide whether the gate moves, so they carry their unit and bounds, pinned by a test.
 */
@Validated
@ConfigurationProperties("gate")
public record GateProperties(
    @NotEmpty List<String> screens,
    @Valid @NotNull Open open,
    @Valid @NotNull Screen screen
) {

    public record Open(
        @NotNull @DurationUnit(ChronoUnit.SECONDS) @DurationMin(seconds = 1) @DurationMax(minutes = 5)
        Duration ignoreRepeatFor
    ) {
    }

    public record Screen(
        @NotNull @DurationUnit(ChronoUnit.SECONDS) @DurationMin(seconds = 5) @DurationMax(minutes = 5) Duration maxAge
    ) {
    }
}
