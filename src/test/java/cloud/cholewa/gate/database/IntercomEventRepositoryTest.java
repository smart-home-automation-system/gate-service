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

//intercom_event of V2 (HAS-448) in a PostgreSQL in Docker: an event stored and read back whole, an unlock stored once
//by its door and time (the partial unique index), the checks of the kind and the door. Without a Docker the test fails
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
class IntercomEventRepositoryTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 17, 16, 13, 37);

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private IntercomEventRepository repository;

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

    private static IntercomEventEntity unlock(final LocalDateTime at, final int door) {
        return IntercomEventEntity.unlock(at, at.plusSeconds(3), "HOUSEHOLDER", "494848494849494848484900",
            "10010110001", door);
    }

    @Test
    void should_store_an_event_and_read_it_back_whole() {
        final IntercomEventEntity unlock = unlock(AT, 1);

        repository.save(unlock)
            .flatMap(saved -> repository.findById(saved.id()))
            .as(StepVerifier::create)
            .assertNext(read -> assertThat(read).usingRecursiveComparison().ignoringFields("id").isEqualTo(unlock))
            .verifyComplete();
    }

    //the attributes published again are the same unlock: the second row is refused by its index, by its name
    @Test
    void should_refuse_an_unlock_of_the_same_door_at_the_same_time() {
        repository.save(unlock(AT, 1))
            .then(repository.save(unlock(AT, 1)))
            .as(StepVerifier::create)
            .expectErrorSatisfies(error -> assertThat(error)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("intercom_event_unlock_uq"))
            .verify();
    }

    //another door, another time, or rings at the same moment are rows of their own
    @Test
    void should_take_unlocks_of_another_door_or_time_and_rings_at_one_moment() {
        repository.save(unlock(AT, 1))
            .then(repository.save(unlock(AT, 2)))
            .then(repository.save(unlock(AT.plusSeconds(1), 1)))
            .then(repository.save(IntercomEventEntity.ring(AT, "10010100000")))
            .then(repository.save(IntercomEventEntity.ring(AT, "10010100000")))
            .then(repository.count())
            .as(StepVerifier::create)
            .expectNext(5L)
            .verifyComplete();
    }

    @Test
    void should_refuse_a_kind_it_does_not_know_and_an_unlock_without_its_door() {
        repository.save(new IntercomEventEntity(null, "MOTION", AT, AT, null, null, null, null, null))
            .as(StepVerifier::create)
            .verifyError(DataIntegrityViolationException.class);
        repository.save(new IntercomEventEntity(null, IntercomEventEntity.UNLOCK, AT, AT, null, "HOUSEHOLDER", null,
                null, null))
            .as(StepVerifier::create)
            .verifyError(DataIntegrityViolationException.class);
        repository.save(new IntercomEventEntity(null, IntercomEventEntity.RING, AT, AT, null, null, null, null, 1))
            .as(StepVerifier::create)
            .verifyError(DataIntegrityViolationException.class);
    }
}
