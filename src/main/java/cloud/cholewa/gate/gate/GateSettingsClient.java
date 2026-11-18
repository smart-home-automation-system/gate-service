package cloud.cholewa.gate.gate;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;

//The settings of the relay of the Shelly 1 Gen1 of the gate: GET /settings/relay/<channel> (no authentication on the
//device). Only auto_off is read - the seconds after which the relay switches itself off, which is what makes "turn on"
//a pulse (1 s on the gate, read on 2026-11-17). shelly-client has no read of the settings of a Gen1 relay; one read,
//here. An answer without auto_off is a failed read, never 0
@Component
public class GateSettingsClient {

    private final WebClient gateShellyWebClient;

    public GateSettingsClient(@Qualifier("gateShellyWebClient") final WebClient gateShellyWebClient) {
        this.gateShellyWebClient = gateShellyWebClient;
    }

    record RelaySettings(@JsonProperty("auto_off") Double autoOff) {
    }

    public Mono<Double> autoOff(final String address, final int channel) {
        return gateShellyWebClient
            .get()
            //the address of the registry, checked there as an IPv4 address; a template would encode the colon of a port
            .uri(URI.create("http://" + address + "/settings/relay/" + channel))
            .retrieve()
            .bodyToMono(RelaySettings.class)
            .flatMap(settings -> Mono.justOrEmpty(settings.autoOff()))
            .switchIfEmpty(Mono.error(() -> new IllegalStateException(
                "The Shelly of the gate at " + address + " answered without auto_off")));
    }
}
