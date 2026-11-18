package cloud.cholewa.gate.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

//HAS-450: gate.retention decides what is deleted - a bare number is days, never milliseconds, and it stays between the
//31 days the events API serves and ten years
class GateRetentionPropertiesTest {

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

    private static GateRetentionProperties bind(final String retention) {
        return new Binder(new MapConfigurationPropertySource(Map.of("gate.retention", retention)))
            .bind("gate", GateRetentionProperties.class).get();
    }

    @ParameterizedTest
    @ValueSource(strings = {"90", "90d"})
    void should_bind_a_bare_number_as_days(final String retention) {
        assertThat(bind(retention).retention()).isEqualTo(Duration.ofDays(90));
    }

    @ParameterizedTest
    @ValueSource(strings = {"31", "3650"})
    void should_take_the_bounds(final String retention) {
        assertThat(validator.validate(bind(retention))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"30", "3651", "7d", "1000ms"})
    void should_refuse_a_retention_outside_the_bounds(final String retention) {
        assertThat(validator.validate(bind(retention))).isNotEmpty();
    }
}
