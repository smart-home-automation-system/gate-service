package cloud.cholewa.gate.gate;

import cloud.cholewa.gate.error.GateException;
import cloud.cholewa.gate.registry.HouseholdProfilesClient;
import cloud.cholewa.home.model.HouseholdProfile;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Locale;

//The member who commands a gate from the dashboard (HAS-449, the garage gates since HAS-474), checked against the
//household profiles of database-service: answers the name as the registry spells it - the case of the request does not
//matter, as the registry keeps names unique ignoring it. 400 UNKNOWN_MEMBER for a name no switched-on member has, 502
//REGISTRY_UNAVAILABLE when the profiles cannot be read
@Component
@RequiredArgsConstructor
public class HouseholdMembers {

    private final HouseholdProfilesClient profilesClient;

    public Mono<String> known(final String member) {
        return profilesClient.getProfiles()
            .onErrorMap(failure -> !(failure instanceof GateException), failure -> new GateException.RegistryUnavailable())
            .flatMap(profiles -> Mono.justOrEmpty(profiles.stream()
                .map(HouseholdProfile::getName)
                .filter(name -> name != null && name.toUpperCase(Locale.ROOT).equals(member.toUpperCase(Locale.ROOT)))
                .findFirst()))
            .switchIfEmpty(Mono.error(() -> new GateException.UnknownMember(member)));
    }
}
