package cloud.cholewa.gate.monitor;

import cloud.cholewa.gate.monitor.AvailabilityEvaluator.Alert;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.r2dbc.test.autoconfigure.DataR2dbcTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

//gate_availability_alert of V7 (HAS-453) in a PostgreSQL in Docker: a row written when due and not told, told with the
//time of its alert, never written twice, deleted. Without a Docker the test fails
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
class AvailabilityAlertRepositoryTest {

    private static final LocalDateTime SINCE = LocalDateTime.of(2026, 11, 19, 10, 1);

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private DatabaseClient databaseClient;

    @DynamicPropertySource
    static void connectToTheContainer(final DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://%s:%d/%s".formatted(
            POSTGRES.getHost(), POSTGRES.getMappedPort(5432), POSTGRES.getDatabaseName()));
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void migrate() {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .load()
            .migrate();
    }

    @Test
    void should_write_a_due_alert_untold_mark_it_told_and_delete_it() {
        final AvailabilityAlertRepository repository = new AvailabilityAlertRepository(databaseClient);
        final Alert due = new Alert("shelly:148", "the Shelly of the garage gate Kate (148)", SINCE, false, null);

        repository.due(due)
            .then(repository.due(new Alert("shelly:148", "another name", SINCE.plusMinutes(5), false, null)))
            .thenMany(repository.findAll())
            .collectList()
            .as(StepVerifier::create)
            .assertNext(rows -> assertThat(rows).containsExactly(due))
            .verifyComplete();

        repository.told("shelly:148", SINCE.plusMinutes(5))
            .thenMany(repository.findAll())
            .as(StepVerifier::create)
            .assertNext(row -> {
                assertThat(row.told()).isTrue();
                assertThat(row.lastAlertAt()).isEqualTo(SINCE.plusMinutes(5));
            })
            .verifyComplete();

        repository.delete("shelly:148").thenMany(repository.findAll()).as(StepVerifier::create).verifyComplete();
    }
}
