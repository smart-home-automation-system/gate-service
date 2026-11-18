package cloud.cholewa.gate.database;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.r2dbc.test.autoconfigure.DataR2dbcTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

//garage_gate_alert of V5 (HAS-475) in a PostgreSQL in Docker: an episode raised, repeated and closed; one open episode
//per gate and kind (the partial unique index); the bound of the repeats. Without a Docker the test fails
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
class GarageGateAlertRepositoryTest {

    private static final LocalDateTime OPENED = LocalDateTime.of(2026, 11, 18, 19, 0);
    private static final LocalDateTime RAISED = OPENED.plusMinutes(15);

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private GarageGateAlertRepository repository;

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

    @BeforeEach
    void clean() {
        repository.deleteAll().as(StepVerifier::create).verifyComplete();
    }

    @Test
    void should_raise_repeat_and_close_an_episode() {
        repository.save(GarageGateAlertEntity.raised(148L, "COLD", OPENED, RAISED))
            .flatMap(raised -> repository.save(raised.reminded(RAISED.plusMinutes(30))))
            .thenMany(repository.findByClosedAtIsNull())
            .as(StepVerifier::create)
            .assertNext(open -> {
                assertThat(open.reminders()).isEqualTo(1);
                assertThat(open.lastMessageAt()).isEqualTo(RAISED.plusMinutes(30));
            })
            .verifyComplete();

        repository.findByClosedAtIsNull()
            .flatMap(open -> repository.save(open.closed(RAISED.plusMinutes(40))))
            .thenMany(repository.findByClosedAtIsNull())
            .as(StepVerifier::create)
            .verifyComplete();
    }

    //a second open episode of the same gate and kind is refused by its index, by its name; another kind is its own
    @Test
    void should_keep_one_open_episode_per_gate_and_kind() {
        repository.save(GarageGateAlertEntity.raised(148L, "COLD", OPENED, RAISED))
            .then(repository.save(GarageGateAlertEntity.raised(148L, "LONG", OPENED, RAISED)))
            .then(repository.save(GarageGateAlertEntity.raised(148L, "COLD", OPENED, RAISED)))
            .as(StepVerifier::create)
            .expectErrorSatisfies(error -> assertThat(error)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("garage_gate_alert_open_uq"))
            .verify();
    }

    @Test
    void should_refuse_an_eleventh_repeat_and_a_kind_it_does_not_know() {
        repository.save(new GarageGateAlertEntity(null, 148L, "COLD", OPENED, RAISED, RAISED, 11, null))
            .as(StepVerifier::create)
            .expectErrorSatisfies(error -> assertThat(error).hasMessageContaining("garage_gate_alert_reminders"))
            .verify();
        repository.save(new GarageGateAlertEntity(null, 148L, "WINDY", OPENED, RAISED, RAISED, 0, null))
            .as(StepVerifier::create)
            .expectErrorSatisfies(error -> assertThat(error).hasMessageContaining("garage_gate_alert_kind"))
            .verify();
    }
}
