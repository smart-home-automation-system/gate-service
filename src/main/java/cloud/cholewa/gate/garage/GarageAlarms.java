package cloud.cholewa.gate.garage;

import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.gate.config.GarageAlarmProperties;
import cloud.cholewa.gate.database.GarageGateAlertEntity;
import cloud.cholewa.gate.database.GarageGateAlertRepository;
import cloud.cholewa.gate.garage.GarageAlarmEvaluator.Action;
import cloud.cholewa.gate.garage.GarageAlarmEvaluator.Closed;
import cloud.cholewa.gate.garage.GarageAlarmEvaluator.EndSilently;
import cloud.cholewa.gate.garage.GarageAlarmEvaluator.Raise;
import cloud.cholewa.gate.garage.GarageAlarmEvaluator.Remind;
import cloud.cholewa.gate.garage.WeatherClient.Temperature;
import cloud.cholewa.gate.gate.GarageGate;
import cloud.cholewa.gate.gate.GateRegistry;
import cloud.cholewa.gate.household.HouseholdNotifier;
import cloud.cholewa.home.model.HouseholdEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

//The alarms of a garage gate left open (HAS-475, replacing the Shelly cloud scene GARAGE NOTIFICATION), judged every
//minute for every garage gate by GarageAlarmEvaluator. One pass reads the open episodes, the temperature outside and who
//is at home - a read that fails is not known (no cold, never empty), never a stop of the pass - then does what the
//evaluator says, gate after gate. An episode is written only once its message was told (raised or repeated), so a
//broker away repeats it the next pass; a closed gate ends its episodes whatever the "closed" came to. Nothing told ever
//fails or stalls the pass (HouseholdNotifier); a write that fails is a WARN. Bounded below the minute of its delay
@Slf4j
@Component
@RequiredArgsConstructor
public class GarageAlarms {

    static final Duration LIMIT = Duration.ofSeconds(50);

    private final GateRegistry gateRegistry;
    private final GarageSensors garageSensors;
    private final WeatherClient weatherClient;
    private final PresenceClient presenceClient;
    private final GarageGateAlertRepository repository;
    private final HouseholdNotifier householdNotifier;
    private final GarageAlarmProperties properties;
    private final Clock clock;

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    Mono<Void> judge() {
        return ScheduledPass.bounded("garage alarms", LIMIT, this::judgeOnce);
    }

    Mono<Void> judgeOnce() {
        return Mono.defer(() -> gateRegistry.garages().isEmpty()
            ? Mono.empty()
            : Mono.zip(openEpisodes(), temperature(), house())
                .flatMapMany(read -> Flux.fromIterable(gateRegistry.garages())
                    .concatMap(gate -> judge(gate.gate(), read.getT1(), read.getT2(), read.getT3())))
                .then());
    }

    private Mono<Map<Long, List<GarageGateAlertEntity>>> openEpisodes() {
        return repository.findByClosedAtIsNull()
            .collect(Collectors.groupingBy(GarageGateAlertEntity::gateId));
    }

    //no temperature, or none that is fresh, is no cold
    private Mono<Optional<Temperature>> temperature() {
        return weatherClient.outside()
            .map(Optional::of)
            .defaultIfEmpty(Optional.empty())
            .onErrorResume(failure -> {
                log.warn("The temperature outside could not be read, no cold alarm this pass: {}", failure.toString());
                return Mono.just(Optional.empty());
            });
    }

    //a presence-service away is a house not known - never empty
    private Mono<HouseOccupancy> house() {
        return presenceClient.residents()
            .map(residents -> HouseOccupancy.judge(residents, LocalDateTime.now(clock)))
            .onErrorResume(failure -> {
                log.warn("Who is at home could not be read, no empty-house alarm this pass: {}", failure.toString());
                return Mono.just(HouseOccupancy.unknown());
            });
    }

    private Mono<Void> judge(final GarageGate gate, final Map<Long, List<GarageGateAlertEntity>> episodes,
                             final Optional<Temperature> temperature, final HouseOccupancy house) {
        final LocalDateTime now = LocalDateTime.now(clock);
        final List<Action> actions = GarageAlarmEvaluator.decide(garageSensors.stateOf(gate, now), temperature, house,
            episodes.getOrDefault(gate.id(), List.of()), now, clock.getZone(), properties);
        return Flux.fromIterable(actions)
            .concatMap(action -> act(gate, action, temperature, now))
            .then();
    }

    private Mono<Void> act(final GarageGate gate, final Action action, final Optional<Temperature> temperature,
                           final LocalDateTime now) {
        return switch (action) {
            case Raise(String kind, LocalDateTime openSince) -> tell(gate, kind, openSince, 0, temperature, now)
                .filter(Boolean::booleanValue)
                .doOnNext(told -> log.info("Garage gate {} ({}): {} since {}", gate.id(), gate.name(), kind, openSince))
                .flatMap(told -> write(GarageGateAlertEntity.raised(gate.id(), kind, openSince, now)));
            case Remind(GarageGateAlertEntity episode) -> tell(gate, episode.kind(), episode.openSince(),
                episode.reminders() + 1, temperature, now)
                .filter(Boolean::booleanValue)
                .flatMap(told -> write(episode.reminded(now)));
            case EndSilently(GarageGateAlertEntity episode) -> write(episode.closed(now));
            case Closed(List<GarageGateAlertEntity> episodes) -> householdNotifier.garageGate(
                    HouseholdEvent.GARAGE_GATE_CLOSED, gate,
                    episodes.stream().map(GarageGateAlertEntity::openSince).min(Comparator.naturalOrder()).orElse(null),
                    0, null, now)
                .thenMany(Flux.fromIterable(episodes))
                .concatMap(episode -> write(episode.closed(now)))
                .then();
        };
    }

    private Mono<Boolean> tell(final GarageGate gate, final String kind, final LocalDateTime openSince,
                               final int reminder, final Optional<Temperature> temperature, final LocalDateTime now) {
        return householdNotifier.garageGate(eventOf(kind), gate, openSince, reminder,
            GarageAlarmEvaluator.COLD.equals(kind) ? temperature.map(Temperature::degrees).orElse(null) : null, now);
    }

    private static HouseholdEvent eventOf(final String kind) {
        return switch (kind) {
            case GarageAlarmEvaluator.COLD -> HouseholdEvent.GARAGE_GATE_OPEN_COLD;
            case GarageAlarmEvaluator.EMPTY_HOUSE -> HouseholdEvent.GARAGE_GATE_OPEN_EMPTY_HOUSE;
            default -> HouseholdEvent.GARAGE_GATE_OPEN_LONG;
        };
    }

    private Mono<Void> write(final GarageGateAlertEntity episode) {
        return repository.save(episode)
            .then()
            .onErrorResume(failure -> {
                log.warn("The alarm episode {} of garage gate {} could not be written: {}", episode.kind(),
                    episode.gateId(), failure.toString());
                return Mono.empty();
            });
    }
}
