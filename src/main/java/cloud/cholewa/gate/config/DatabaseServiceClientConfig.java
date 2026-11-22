package cloud.cholewa.gate.config;

import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

//The client of database-service (the device registry, the household profiles), of weather-service, presence-service and
//amx-service, and the client of the settings of the Shelly of the gate. Built from the injected builder, so the calls are traced and logged by Logbook (cholewa-commons >=
//1.10.0); the timeouts keep a silent service or device from holding a pass or a request
@Configuration
public class DatabaseServiceClientConfig {

    static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(5);
    static final int CONNECT_TIMEOUT_MILLIS = 5000;

    //Without a limit an idle connection stays in the pool for ever: after a restart of database-service, or once
    //conntrack has forgotten it, the next call is written onto a dead socket (the lesson of the gateway, HAS-212)
    static final Duration MAX_IDLE_TIME = Duration.ofMinutes(2);
    static final Duration MAX_LIFE_TIME = Duration.ofMinutes(30);

    //Spring infers only close() and shutdown() as destroy methods, so the pool is named explicitly
    @Bean(destroyMethod = "dispose")
    ConnectionProvider databaseServiceConnectionProvider() {
        return connectionProvider("database-service");
    }

    @Bean
    WebClient databaseServiceWebClient(
        final WebClient.Builder webClientBuilder,
        final ConnectionProvider databaseServiceConnectionProvider,
        final InternalServicesProperties services
    ) {
        return client(webClientBuilder, databaseServiceConnectionProvider).baseUrl(services.database().toString()).build();
    }

    //weather-service and presence-service (HAS-475): the temperature outside and who is at home - a pool each
    @Bean(destroyMethod = "dispose")
    ConnectionProvider weatherServiceConnectionProvider() {
        return connectionProvider("weather-service");
    }

    @Bean
    WebClient weatherServiceWebClient(
        final WebClient.Builder webClientBuilder,
        final ConnectionProvider weatherServiceConnectionProvider,
        final InternalServicesProperties services
    ) {
        return client(webClientBuilder, weatherServiceConnectionProvider).baseUrl(services.weather().toString()).build();
    }

    @Bean(destroyMethod = "dispose")
    ConnectionProvider presenceServiceConnectionProvider() {
        return connectionProvider("presence-service");
    }

    @Bean
    WebClient presenceServiceWebClient(
        final WebClient.Builder webClientBuilder,
        final ConnectionProvider presenceServiceConnectionProvider,
        final InternalServicesProperties services
    ) {
        return client(webClientBuilder, presenceServiceConnectionProvider).baseUrl(services.presence().toString()).build();
    }

    //amx-service (HAS-485): the entrance panel woken by a ring - its own pool
    @Bean(destroyMethod = "dispose")
    ConnectionProvider amxServiceConnectionProvider() {
        return connectionProvider("amx-service");
    }

    @Bean
    WebClient amxServiceWebClient(
        final WebClient.Builder webClientBuilder,
        final ConnectionProvider amxServiceConnectionProvider,
        final InternalServicesProperties services
    ) {
        return client(webClientBuilder, amxServiceConnectionProvider).baseUrl(services.amx().toString()).build();
    }

    //the settings of the Shelly of the gate, read at its address of the registry - its own pool
    @Bean(destroyMethod = "dispose")
    ConnectionProvider gateShellyConnectionProvider() {
        return connectionProvider("gate-shelly");
    }

    @Bean
    WebClient gateShellyWebClient(
        final WebClient.Builder webClientBuilder,
        final ConnectionProvider gateShellyConnectionProvider
    ) {
        return client(webClientBuilder, gateShellyConnectionProvider).build();
    }

    static ConnectionProvider connectionProvider(final String name) {
        return ConnectionProvider.builder(name)
            .maxIdleTime(MAX_IDLE_TIME)
            .maxLifeTime(MAX_LIFE_TIME)
            .build();
    }

    //the builder is a prototype bean: every client gets its own copy
    private static WebClient.Builder client(
        final WebClient.Builder webClientBuilder,
        final ConnectionProvider connectionProvider
    ) {
        final HttpClient httpClient = HttpClient.create(connectionProvider)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
            .responseTimeout(RESPONSE_TIMEOUT);

        return webClientBuilder.clone().clientConnector(new ReactorClientHttpConnector(httpClient));
    }
}
