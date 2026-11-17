package cloud.cholewa.gate;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

//The document a pod runs with, read as it is written: a context test runs under the test profile, which sets every
//password, and cholewa.required-properties is satisfied by any value - neither sees a password written in place of its
//variable. 0.2.0 shipped "password: password" for the broker (a placeholder lost when the file was written) and could
//not log in (HAS-448)
class ApplicationYamlTest {

    @Test
    void should_take_every_password_from_its_variable() throws IOException {
        final List<PropertySource<?>> documents =
            new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"));
        final PropertySource<?> shared = documents.getFirst();

        assertThat(shared.getProperty("spring.rabbitmq.password")).hasToString("${rabbitmq-password}");
        assertThat(shared.getProperty("database.password")).hasToString("${database-password}");
    }
}
