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

//HAS-473: gate.garage.gateway-silent-after decides when a gate is not known - a bare number is minutes, never
//milliseconds, and it holds at least two reads of the gateway (every five minutes)
class GaragePropertiesTest {

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

    private static GarageProperties bind(final String silentAfter) {
        return new Binder(new MapConfigurationPropertySource(Map.of("gate.garage.gateway-silent-after", silentAfter)))
            .bind("gate.garage", GarageProperties.class).get();
    }

    @ParameterizedTest
    @ValueSource(strings = {"30", "30m"})
    void should_bind_a_bare_number_as_minutes(final String silentAfter) {
        assertThat(bind(silentAfter).gatewaySilentAfter()).isEqualTo(Duration.ofMinutes(30));
    }

    @ParameterizedTest
    @ValueSource(strings = {"10", "24h"})
    void should_take_the_bounds(final String silentAfter) {
        assertThat(validator.validate(bind(silentAfter))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"9", "25h", "1000ms"})
    void should_refuse_a_silence_outside_the_bounds(final String silentAfter) {
        assertThat(validator.validate(bind(silentAfter))).isNotEmpty();
    }
}
