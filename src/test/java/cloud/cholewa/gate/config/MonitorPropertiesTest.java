package cloud.cholewa.gate.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

//HAS-453: the durations of the monitor decide when the owner is told - a bare number is minutes, never milliseconds,
//each within its bounds
class MonitorPropertiesTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static MonitorProperties bind(final Map<String, String> overrides) {
        final Map<String, String> values = new HashMap<>(Map.of(
            "gate.monitor.offline-after", "5",
            "gate.monitor.reminder-every", "60"));
        values.putAll(overrides);
        return new Binder(new MapConfigurationPropertySource(values)).bind("gate.monitor", MonitorProperties.class).get();
    }

    @Test
    void should_bind_bare_numbers_as_minutes() {
        final MonitorProperties properties = bind(Map.of());

        assertThat(properties.offlineAfter()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.reminderEvery()).isEqualTo(Duration.ofHours(1));
        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    void should_refuse_values_outside_the_bounds() {
        assertThat(validator.validate(bind(Map.of("gate.monitor.offline-after", "5000ms")))).isNotEmpty();
        assertThat(validator.validate(bind(Map.of("gate.monitor.offline-after", "2h")))).isNotEmpty();
        assertThat(validator.validate(bind(Map.of("gate.monitor.reminder-every", "5")))).isNotEmpty();
        assertThat(validator.validate(bind(Map.of("gate.monitor.reminder-every", "25h")))).isNotEmpty();
    }
}
