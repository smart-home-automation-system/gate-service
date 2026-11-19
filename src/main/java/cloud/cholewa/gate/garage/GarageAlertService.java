package cloud.cholewa.gate.garage;

import cloud.cholewa.gate.api.model.GarageAlertReply;
import cloud.cholewa.gate.database.GarageGateAlertEntity;
import cloud.cholewa.gate.database.GarageGateAlertRepository;
import cloud.cholewa.gate.gate.GarageGate;
import cloud.cholewa.gate.gate.GateRegistry;
import cloud.cholewa.gate.gate.GateRegistry.GarageGateState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Comparator;
import java.util.Map;

//The open alarms and warnings of the garage gates (HAS-454): the episodes of garage_gate_alert not closed yet (HAS-475 -
//an episode ends when its gate closes or its condition is gone), the newest first, each with the name of its gate as the
//registry has it. The words of a kind are the wire contract of the dashboard: cold, empty house, long
@Service
@RequiredArgsConstructor
public class GarageAlertService {

    private static final Map<String, String> KINDS = Map.of(
        GarageAlarmEvaluator.COLD, "cold",
        GarageAlarmEvaluator.EMPTY_HOUSE, "empty house",
        GarageAlarmEvaluator.LONG, "long");

    private final GarageGateAlertRepository alertRepository;
    private final GateRegistry gateRegistry;

    public Flux<GarageAlertReply> open() {
        return alertRepository.findByClosedAtIsNull()
            .sort(Comparator.comparing(GarageGateAlertEntity::raisedAt).reversed())
            .map(this::reply);
    }

    private GarageAlertReply reply(final GarageGateAlertEntity alert) {
        final String name = gateRegistry.garage(alert.gateId()).map(GarageGateState::gate).map(GarageGate::name)
            .orElse(null);
        final boolean warning = GarageAlarmEvaluator.LONG.equals(alert.kind());
        return new GarageAlertReply(alert.gateId(), name, KINDS.getOrDefault(alert.kind(), alert.kind()),
            warning ? GarageAlertReply.WARNING : GarageAlertReply.ALARM, alert.openSince(), alert.raisedAt(),
            alert.reminders());
    }
}
