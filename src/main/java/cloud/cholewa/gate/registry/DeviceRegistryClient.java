package cloud.cholewa.gate.registry;

import cloud.cholewa.commons.error.DownstreamErrors;
import cloud.cholewa.home.model.DeviceKind;
import cloud.cholewa.home.model.SmartDevice;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

//The switched-on gates and door stations (the kind intercom, HAS-452) of the device registry of database-service (the kind gate, smart-home-sdk 1.22.0). What a
//failing database-service says goes to the log, never further; no body at all, or one that is not a list, is a failed
//call - never an empty registry, which would forget the gate. The shape of the client of appliance-service
@Slf4j
@Component
public class DeviceRegistryClient {

    private static final ParameterizedTypeReference<List<SmartDevice>> DEVICES = new ParameterizedTypeReference<>() {
    };

    private final WebClient databaseServiceWebClient;

    public DeviceRegistryClient(@Qualifier("databaseServiceWebClient") final WebClient databaseServiceWebClient) {
        this.databaseServiceWebClient = databaseServiceWebClient;
    }

    public Mono<List<SmartDevice>> getGates() {
        return getDevices(DeviceKind.GATE, "gates");
    }

    public Mono<List<SmartDevice>> getIntercoms() {
        return getDevices(DeviceKind.INTERCOM, "door stations");
    }

    //the BLU Door/Window sensors the garage gates name (HAS-473) - read by kind, the gates pick theirs
    public Mono<List<SmartDevice>> getOpeningSensors() {
        return getDevices(DeviceKind.OPENING_SENSOR, "opening sensors");
    }

    //the Shelly devices that hear the BLU sensors and hold their state (HAS-473)
    public Mono<List<SmartDevice>> getBthomeGateways() {
        return getDevices(DeviceKind.BTHOME_GATEWAY, "BTHome gateways");
    }

    private Mono<List<SmartDevice>> getDevices(final DeviceKind kind, final String what) {
        return databaseServiceWebClient
            .get()
            .uri(uriBuilder -> uriBuilder
                .path("/home/device/registry")
                .queryParam("kind", kind.getValue())
                .queryParam("enabled", true)
                .build()
            )
            .retrieve()
            .onStatus(HttpStatusCode::isError, response -> DownstreamErrors.read(response)
                .map(error -> {
                    log.warn("database-service answered {} to the read of the {}: {}",
                        error.status().value(), what, error.errors());
                    return new RegistryCallException("database-service answered " + error.status().value());
                }))
            .bodyToMono(DEVICES)
            .switchIfEmpty(Mono.error(() -> new RegistryCallException("database-service answered without a list")));
    }
}
