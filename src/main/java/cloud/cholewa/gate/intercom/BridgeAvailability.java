package cloud.cholewa.gate.intercom;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

//The availability of the bridge as it publishes it, per entity ("online" / "offline" on <entity>.availability - its
//last will says offline for every entity at once when it drops off the broker). Kept in memory: the read of the gate
//(HAS-450) and the availability monitor (HAS-453) take it from here. Unknown until the bridge says something
@Component
public class BridgeAvailability {

    private static final String ONLINE = "online";
    private static final String OFFLINE = "offline";

    private final Map<String, Report> reports = new ConcurrentHashMap<>();

    public record Report(boolean online, LocalDateTime at) {
    }

    //a word the bridge does not use changes nothing - it is no news of the bridge
    public void report(final String entity, final String payload, final LocalDateTime at) {
        if (ONLINE.equals(payload)) {
            reports.put(entity, new Report(true, at));
        } else if (OFFLINE.equals(payload)) {
            reports.put(entity, new Report(false, at));
        }
    }

    //the newest report of any entity: they all go together, online with the bridge and offline with its last will
    public Optional<Report> latest() {
        return reports.values().stream().max(Comparator.comparing(Report::at));
    }
}
