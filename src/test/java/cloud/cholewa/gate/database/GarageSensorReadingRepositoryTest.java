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

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

//garage_sensor_reading of V4 (HAS-473) in a PostgreSQL in Docker: one row per sensor, an older reading never replaces a
//newer one (the upsert is PostgreSQL's own), the rows of the sensors no gate names deleted. Without a Docker the test fails
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
class GarageSensorReadingRepositoryTest {

    private static final Instant HEARD = Instant.parse("2026-11-18T18:44:57Z");
    private static final LocalDateTime STORED = LocalDateTime.of(2026, 11, 18, 19, 45);

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private GarageSensorReadingRepository repository;

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
    void should_keep_one_row_per_sensor_and_never_an_older_reading() {
        repository.upsert(69L, true, HEARD, STORED)
            .then(repository.upsert(69L, false, HEARD.plusSeconds(30), STORED.plusSeconds(30)))
            //a gateway restarted gives the open of before with its old time: refused by the statement
            .then(repository.upsert(69L, true, HEARD, STORED.plusSeconds(60)))
            .thenMany(repository.findAll())
            .as(StepVerifier::create)
            .assertNext(row -> assertThat(row).isEqualTo(
                new GarageSensorReadingEntity(69L, false, HEARD.plusSeconds(30), STORED.plusSeconds(30))))
            .verifyComplete();
    }

    @Test
    void should_delete_the_rows_of_the_sensors_no_gate_names() {
        repository.upsert(69L, true, HEARD, STORED)
            .then(repository.upsert(70L, false, null, STORED))
            .then(repository.deleteOthers(List.of(70L)))
            .thenMany(repository.findAll())
            .map(GarageSensorReadingEntity::sensorId)
            .as(StepVerifier::create)
            .expectNext(70L)
            .verifyComplete();
    }
}
