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

//gate_command of V3 (HAS-449) in a PostgreSQL in Docker: the last opening the Shelly took, and the checks of the source,
//the outcome and the member. Without a Docker the test fails
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
class GateCommandRepositoryTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 18, 9, 25);

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private GateCommandRepository repository;

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

    //the newest opening the Shelly took - not a refused or failed one after it
    @Test
    void should_find_the_last_opening_the_shelly_took() {
        repository.save(GateCommandEntity.of(AT, "SCREEN", null, GateCommandEntity.OPENED))
            .then(repository.save(GateCommandEntity.of(AT.plusMinutes(5), "DASHBOARD", "Anna", GateCommandEntity.OPENED)))
            .then(repository.save(GateCommandEntity.of(AT.plusMinutes(6), "DASHBOARD", "Anna", GateCommandEntity.TOO_SOON)))
            .then(repository.save(GateCommandEntity.of(AT.plusMinutes(7), "SCREEN", null, GateCommandEntity.FAILED)))
            .then(repository.findFirstByOutcomeOrderByAtDesc(GateCommandEntity.OPENED))
            .as(StepVerifier::create)
            .assertNext(last -> {
                assertThat(last.at()).isEqualTo(AT.plusMinutes(5));
                assertThat(last.member()).isEqualTo("Anna");
            })
            .verifyComplete();
    }

    @Test
    void should_refuse_a_source_or_outcome_it_does_not_know_and_a_member_of_the_screen() {
        repository.save(GateCommandEntity.of(AT, "REMOTE", null, GateCommandEntity.OPENED))
            .as(StepVerifier::create).verifyError(DataIntegrityViolationException.class);
        repository.save(GateCommandEntity.of(AT, "SCREEN", null, "MAYBE"))
            .as(StepVerifier::create).verifyError(DataIntegrityViolationException.class);
        repository.save(GateCommandEntity.of(AT, "SCREEN", "Anna", GateCommandEntity.OPENED))
            .as(StepVerifier::create).verifyError(DataIntegrityViolationException.class);
        repository.save(GateCommandEntity.of(AT, "DASHBOARD", null, GateCommandEntity.OPENED))
            .as(StepVerifier::create).verifyError(DataIntegrityViolationException.class);
        repository.save(GateCommandEntity.of(AT, "PANEL", "Anna", GateCommandEntity.OPENED))
            .as(StepVerifier::create).verifyError(DataIntegrityViolationException.class);
    }

    //HAS-483, V8: the entrance panel, without a member
    @Test
    void should_store_an_opening_from_the_panel() {
        repository.save(GateCommandEntity.of(AT, "PANEL", null, GateCommandEntity.OPENED))
            .as(StepVerifier::create).expectNextCount(1).verifyComplete();
    }

    //HAS-474, V6: a pulse goes to a garage gate - stored with its id; a pulse without one is refused by its check
    @Test
    void should_store_the_pulse_of_a_garage_gate_with_its_gate_only() {
        repository.save(GateCommandEntity.ofGarage(148, AT, "DASHBOARD", "Anna", GateCommandEntity.PULSED))
            .flatMap(saved -> repository.findById(saved.id()))
            .as(StepVerifier::create)
            .assertNext(read -> {
                assertThat(read.gateId()).isEqualTo(148L);
                assertThat(read.outcome()).isEqualTo(GateCommandEntity.PULSED);
            })
            .verifyComplete();
        repository.save(GateCommandEntity.of(AT, "DASHBOARD", "Anna", GateCommandEntity.PULSED))
            .as(StepVerifier::create)
            .expectErrorSatisfies(error -> assertThat(error).hasMessageContaining("gate_command_pulse_of_a_garage_gate"))
            .verify();
    }

    //HAS-450: from included, to not, the newest first
    @Test
    void should_read_the_commands_of_a_period_newest_first() {
        repository.save(GateCommandEntity.of(AT.minusSeconds(1), "SCREEN", null, GateCommandEntity.OPENED))
            .then(repository.save(GateCommandEntity.of(AT, "SCREEN", null, GateCommandEntity.OPENED)))
            .then(repository.save(GateCommandEntity.of(AT.plusMinutes(1), "DASHBOARD", "Anna", GateCommandEntity.TOO_SOON)))
            .then(repository.save(GateCommandEntity.of(AT.plusMinutes(2), "SCREEN", null, GateCommandEntity.OPENED)))
            .thenMany(repository.findInPeriod(AT, AT.plusMinutes(2)))
            .map(GateCommandEntity::outcome)
            .collectList()
            .as(StepVerifier::create)
            .assertNext(outcomes -> assertThat(outcomes).containsExactly(GateCommandEntity.TOO_SOON, GateCommandEntity.OPENED))
            .verifyComplete();
    }

    //HAS-450: the retention deletes what is older than its bound, not the command at it
    @Test
    void should_delete_the_commands_before_a_bound() {
        repository.save(GateCommandEntity.of(AT.minusSeconds(1), "SCREEN", null, GateCommandEntity.OPENED))
            .then(repository.save(GateCommandEntity.of(AT, "SCREEN", null, GateCommandEntity.FAILED)))
            .then(repository.deleteBefore(AT))
            .as(StepVerifier::create).expectNext(1L).verifyComplete();

        repository.findAll().map(GateCommandEntity::outcome).collectList().as(StepVerifier::create)
            .assertNext(outcomes -> assertThat(outcomes).containsExactly(GateCommandEntity.FAILED))
            .verifyComplete();
    }
}
