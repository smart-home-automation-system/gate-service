package cloud.cholewa.gate.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;

/**
 * The services this one calls inside the cluster, by k8s DNS. {@code database} - database-service: the Shelly of the
 * gate (the kind {@code gate} of the device registry) and the household profiles an opening from the dashboard is
 * checked against (HAS-449). {@code weather} - weather-service: the temperature outside, for the cold alarm of a garage
 * gate; {@code presence} - presence-service: who is at home, for its empty-house alarm (HAS-475). {@code amx} -
 * amx-service: the entrance panel woken by a ring (HAS-485). No default: a made-up address would start the service and
 * fail every read.
 */
@Validated
@ConfigurationProperties("internal.service")
public record InternalServicesProperties(
    @NotNull URI database,
    @NotNull URI weather,
    @NotNull URI presence,
    @NotNull URI amx
) {
}
