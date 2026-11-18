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

//HAS-475: the durations of the alarms decide when the household is told - a bare number is minutes, never milliseconds,
//each within its bounds
class GarageAlarmPropertiesTest {

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

    private static GarageAlarmProperties bind(final Map<String, String> overrides) {
        final Map<String, String> values = new HashMap<>(Map.of(
            "gate.garage.alarm.cold-open-for", "15",
            "gate.garage.alarm.cold-below", "15.0",
            "gate.garage.alarm.empty-for", "5",
            "gate.garage.alarm.long-after", "30",
            "gate.garage.alarm.alarm-every", "30",
            "gate.garage.alarm.warning-every", "60"));
        values.putAll(overrides);
        return new Binder(new MapConfigurationPropertySource(values))
            .bind("gate.garage.alarm", GarageAlarmProperties.class).get();
    }

    @Test
    void should_bind_bare_numbers_as_minutes() {
        final GarageAlarmProperties properties = bind(Map.of());

        assertThat(properties.coldOpenFor()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.emptyFor()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.warningEvery()).isEqualTo(Duration.ofHours(1));
        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    void should_refuse_values_outside_the_bounds() {
        assertThat(validator.validate(bind(Map.of("gate.garage.alarm.cold-open-for", "1000ms")))).isNotEmpty();
        assertThat(validator.validate(bind(Map.of("gate.garage.alarm.alarm-every", "1")))).isNotEmpty();
        assertThat(validator.validate(bind(Map.of("gate.garage.alarm.cold-below", "40")))).isNotEmpty();
        assertThat(validator.validate(bind(Map.of("gate.garage.alarm.long-after", "25h")))).isNotEmpty();
    }
}
