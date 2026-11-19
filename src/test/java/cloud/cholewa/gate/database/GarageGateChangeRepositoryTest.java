package cloud.cholewa.gate.database;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.r2dbc.test.autoconfigure.DataR2dbcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

//garage_gate_change of V6 (HAS-474) in a PostgreSQL in Docker: the changes of a period newest first, from included and
//to not; an opening carries no since; the nightly delete. Without a Docker the test fails
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
class GarageGateChangeRepositoryTest {

    private static final LocalDateTime OPENED = LocalDateTime.of(2026, 11, 18, 21, 31, 54);
    private static final LocalDateTime CLOSED = LocalDateTime.of(2026, 11, 18, 21, 34, 23);

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private GarageGateChangeRepository repository;

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
    void should_read_the_changes_of_a_period_newest_first_and_delete_the_old() {
        repository.save(GarageGateChangeEntity.of(148, OPENED.minusDays(100), true, null))
            .then(repository.save(GarageGateChangeEntity.of(148, OPENED, true, null)))
            .then(repository.save(GarageGateChangeEntity.of(148, CLOSED, false, OPENED)))
            .thenMany(repository.findInPeriod(OPENED, CLOSED.plusSeconds(1)))
            .collectList()
            .as(StepVerifier::create)
            .assertNext(changes -> {
                assertThat(changes).extracting(GarageGateChangeEntity::at).containsExactly(CLOSED, OPENED);
                assertThat(changes.getFirst().openSince()).isEqualTo(OPENED);
                assertThat(changes.getLast().openSince()).isNull();
            })
            .verifyComplete();

        repository.deleteBefore(OPENED.minusDays(90)).as(StepVerifier::create).expectNext(1L).verifyComplete();
    }

    //an opening has no since, by the model and by the check of the table
    @Test
    void should_keep_no_since_on_an_opening() {
        repository.save(new GarageGateChangeEntity(null, 148L, OPENED, true, OPENED.minusHours(1)))
            .as(StepVerifier::create)
            .expectErrorSatisfies(error -> assertThat(error).hasMessageContaining("garage_gate_change_open_since"))
            .verify();
        assertThat(GarageGateChangeEntity.of(148, OPENED, true, OPENED.minusHours(1)).openSince()).isNull();
    }
}
