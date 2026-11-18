package cloud.cholewa.gate;

import cloud.cholewa.commons.database.DatabaseProperties;
import cloud.cholewa.gate.config.GateProperties;
import cloud.cholewa.gate.config.IntercomProperties;
import cloud.cholewa.shelly.http.ShellyGen1Client;
import cloud.cholewa.shelly.http.ShellyRpcClient;
import cloud.cholewa.shelly.codec.ShellyMqttDecoder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class GateServiceApplicationTest {

    @Autowired
    private DatabaseProperties databaseProperties;
    @Autowired
    private ApplicationContext applicationContext;
    @Autowired
    private IntercomProperties intercomProperties;
    @Autowired
    private GateProperties gateProperties;

    @Test
    void contextLoads() {
    }

    //the pool is this service's share of the 47 connections of the managed database, counted twice during a
    //rollout; a lost or mistyped key falls back to the library default without a sound
    @Test
    void should_keep_the_connection_pool_at_its_share_of_the_database_budget() {
        assertThat(databaseProperties.pool().maxSize()).isEqualTo(2);
    }

    //a context of a test must not read the registry nor reach the gate of the house
    @Test
    void should_schedule_nothing_under_the_test_profile() {
        assertThat(applicationContext.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
    }

    //the scheduler of cholewa-commons >= 1.11.0 (HAS-300): the reading tasks run on it
    @Test
    void should_give_the_scheduled_tasks_a_pool() {
        assertThat(applicationContext.getEnvironment().getProperty("spring.task.scheduling.pool.size", Integer.class))
            .isEqualTo(10);
    }

    //the passwords of the database and of the broker have no default: the start is refused without either
    //(cholewa.required-properties)
    @Test
    void should_require_the_passwords_of_the_database_and_of_the_broker() {
        assertThat(applicationContext.getEnvironment().getProperty("cholewa.required-properties[0].property"))
            .isEqualTo("database.password");
        assertThat(applicationContext.getEnvironment().getProperty("cholewa.required-properties[0].variable"))
            .isEqualTo("database-password");
        assertThat(applicationContext.getEnvironment().getProperty("cholewa.required-properties[1].property"))
            .isEqualTo("spring.rabbitmq.password");
        assertThat(applicationContext.getEnvironment().getProperty("cholewa.required-properties[1].variable"))
            .isEqualTo("rabbitmq-password");
        assertThat(applicationContext.getEnvironment().getProperty("cholewa.required-properties[2].property")).isNull();
    }

    //the opening of the gate (HAS-449): the screen of the house, the repeat of 15 s and the age of 60 s - both durations
    //bound in seconds, not milliseconds
    @Test
    void should_bind_the_opening_of_the_gate() {
        assertThat(gateProperties.screens()).containsExactly("10010110001");
        assertThat(gateProperties.open().ignoreRepeatFor()).isEqualTo(Duration.ofSeconds(15));
        assertThat(gateProperties.screen().maxAge()).isEqualTo(Duration.ofSeconds(60));
    }

    //the events of the door station (HAS-448): the queue of every device, the user device of /device, manual acks
    @Test
    void should_consume_the_device_queue_as_the_device_user_with_manual_acks() {
        assertThat(applicationContext.getEnvironment().getProperty("spring.rabbitmq.username")).isEqualTo("device");
        assertThat(applicationContext.getEnvironment().getProperty("spring.rabbitmq.virtual-host")).isEqualTo("/device");
        assertThat(applicationContext.getEnvironment().getProperty("spring.rabbitmq.listener.simple.acknowledge-mode"))
            .isEqualTo("manual");
        assertThat(intercomProperties.device()).isEqualTo("gate");
        assertThat(intercomProperties.bridgeZone()).isEqualTo(ZoneId.of("UTC"));
    }


    //the Shelly 1 of the gate (HAS-449) is driven through the Gen1 client of shelly-client
    @Test
    void should_have_the_clients_and_the_decoder_of_shelly_client() {
        assertThat(applicationContext.getBeanNamesForType(ShellyRpcClient.class)).hasSize(1);
        assertThat(applicationContext.getBeanNamesForType(ShellyGen1Client.class)).hasSize(1);
        assertThat(applicationContext.getBeanNamesForType(ShellyMqttDecoder.class)).hasSize(1);
    }
}
