package cloud.cholewa.gate.garage;

import cloud.cholewa.gate.config.GarageProperties;
import cloud.cholewa.gate.gate.GarageGate;
import cloud.cholewa.gate.gate.GateRegistry;
import cloud.cholewa.gate.registry.DeviceRegistryClient;
import cloud.cholewa.home.model.SmartDevice;
import cloud.cholewa.shelly.http.ShellyRpcClient;
import cloud.cholewa.shelly.model.BthomeSensorConfig;
import cloud.cholewa.shelly.model.BthomeSensorStatus;
import cloud.cholewa.shelly.model.ShellyComponent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

//The sensors of the garage gates (HAS-473): Shelly BLU Door/Window sensors adopted by BTHome gateways, each a device of
//the kind opening sensor in the registry, named in the openingSensors of its garage gate. The copy of WindowSensors of
//blinds-service (HAS-290, HAS-420), the third with heating-service, kept by the rule of three copies.
//Which bthomesensor component of which gateway is the window object of which sensor is read from the gateways
//themselves, every five minutes, with the state each one holds. Between two reads the state comes from the status
//topic of the component (GarageEventRouter), about once a change. A sensor sends only when it changes (no beacon), so
//its own silence proves nothing: a state stands while its gateway confirms it, by a read or a publish, within
//gate.garage.gateway-silent-after. A sensor that never reported, or whose gateway went silent, is not known - a failing
//sensor never passes for a closed gate. A gate is judged by its sensors: open when one reports open, not known when one
//is not known, closed when every one is closed, since the time its gateway heard the reading that decides it. The last
//reading of every sensor is stored (GarageSensorStore) and read back at the start as not confirmed: the history a
//gateway value is compared with, never a state the gate is told by
@Slf4j
@Component
public class GarageSensors {

    //the BTHome object of a window or a door: true is open
    static final int WINDOW_OBJECT = 0x2D;
    private static final String SENSOR = "bthomesensor:";

    private final DeviceRegistryClient deviceRegistryClient;
    private final GateRegistry gateRegistry;
    private final ShellyRpcClient shellyRpcClient;
    //a firmware adds fields to a config, and the models have only what is read here
    private final JsonMapper jsonMapper;
    private final GarageProperties properties;
    private final GarageSensorStore store;
    private final Clock clock;

    //the opening sensors the garage gates name, by their id, as last read
    private final AtomicReference<Map<Long, Sensor>> sensors = new AtomicReference<>(Map.of());
    //the BTHome gateways of the registry, as last read
    private final AtomicReference<List<Gateway>> gateways = new AtomicReference<>(List.of());
    //which component of which gateway is the window object of which sensor
    private final Map<SensorComponent, Long> components = new ConcurrentHashMap<>();
    private final Map<Long, Reading> readings = new ConcurrentHashMap<>();
    //the gateways the last read failed on: a WARN when one starts failing, an INFO when it is back
    private final Set<String> failing = ConcurrentHashMap.newKeySet();
    //the sensors a gateway gave an older value of since the start: an INFO once per sensor
    private final Set<Long> olderTold = ConcurrentHashMap.newKeySet();
    //the sensors the stored readings were last trimmed to: the table is trimmed only when the gates name others
    private final AtomicReference<Set<Long>> trimmedTo = new AtomicReference<>(Set.of());

    public GarageSensors(
        final DeviceRegistryClient deviceRegistryClient,
        final GateRegistry gateRegistry,
        final ShellyRpcClient shellyRpcClient,
        final JsonMapper jsonMapper,
        final GarageProperties properties,
        final GarageSensorStore store,
        final Clock clock
    ) {
        this.deviceRegistryClient = deviceRegistryClient;
        this.gateRegistry = gateRegistry;
        this.shellyRpcClient = shellyRpcClient;
        this.jsonMapper = jsonMapper.rebuild().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        this.properties = properties;
        this.store = store;
        this.clock = clock;
    }

    /** An opening sensor of the registry: its Bluetooth address, null for one that is no BTHome sensor. */
    record Sensor(long id, String name, String address) {
    }

    /** A bthomesensor component of a gateway, by the MQTT id of the gateway in lower case. */
    record SensorComponent(String gateway, int component) {
    }

    /**
     * What a sensor last said: {@code open} null until it said anything; {@code updatedAt} when the gateway heard it;
     * {@code confirmedAt} when a gateway last stood for it, null for a reading read back from the table that no gateway
     * has stood for since the start.
     */
    record Reading(Boolean open, Instant updatedAt, LocalDateTime confirmedAt) {
    }

    record Gateway(long id, String name, String address, String deviceId) {
    }

    /**
     * What the sensors of a garage gate say now: {@code since} - when its gateway heard the reading that decides it (the
     * house time), left out when not known; {@code confirmedAt} - when a gateway last stood for it.
     */
    public record GarageReading(GarageState state, LocalDateTime since, LocalDateTime confirmedAt) {
    }

    //the stored readings, before the first read of the gateways (GarageReadingLoader): each one not confirmed, so not
    //known - only what a gateway value is compared with. Never fails; a reading already in memory is kept
    public Mono<Void> restore() {
        return store.load()
            .filter(stored -> readings.putIfAbsent(stored.sensorId(), stored.reading()) == null)
            .count()
            .doOnNext(count -> log.info("{} stored garage sensor reading(s) restored, not known until a gateway stands "
                + "for them", count))
            .then();
    }

    //Never fails. A registry away keeps the last sensors and gateways, and the gateways are read all the same - a
    //database-service down for an hour must not make every gate not known; a gateway away keeps its map and lets its
    //sensors age until they are not known
    //No garage gate known yet (the registry of the gates not read since the start) is no pass at all: the readings
    //restored from the table stay for the first pass that knows the gates
    public Mono<Void> refresh() {
        return Mono.fromSupplier(this::namedByTheGates)
            .filter(names -> !names.isEmpty())
            .flatMap(names -> Mono.zip(deviceRegistryClient.getOpeningSensors(), deviceRegistryClient.getBthomeGateways())
                .map(read -> new SensorsAndGateways(names, read.getT1(), read.getT2())))
            .flatMap(read -> {
                final Map<Long, Sensor> named = toSensors(read.sensors(), read.names());
                sensors.set(named);
                //a sensor no gate names any more is forgotten, a reading restored for it too
                readings.keySet().retainAll(named.keySet());
                final List<Gateway> listedNow = toGateways(read.gateways());
                gateways.set(listedNow);
                //a gateway the registry no longer lists is read no more: its components go with it
                final Set<String> listed = listedNow.stream().map(Gateway::deviceId).collect(Collectors.toSet());
                components.keySet().removeIf(key -> !listed.contains(key.gateway()));
                failing.retainAll(listed);
                return forgetTheGone(named.keySet()).thenReturn(listedNow);
            })
            .onErrorResume(e -> {
                log.warn("The garage sensors could not be read from the registry, the last ones stay: {}", e.toString());
                return Mono.just(gateways.get());
            })
            //no sensor of a gate: no gateway to ask
            .filter(listed -> !sensors.get().isEmpty())
            .flatMapMany(Flux::fromIterable)
            .concatMap(this::read)
            .then();
    }

    //what one pass reads: the sensors the gates name, the opening sensors and the BTHome gateways of the registry
    private record SensorsAndGateways(Set<Long> names, List<SmartDevice> sensors, List<SmartDevice> gateways) {
    }

    private Set<Long> namedByTheGates() {
        return gateRegistry.garages().stream()
            .flatMap(state -> state.gate().sensors().stream())
            .collect(Collectors.toUnmodifiableSet());
    }

    private Mono<Void> read(final Gateway gateway) {
        return shellyRpcClient.dynamicComponents(gateway.address())
            .map(list -> {
                final List<Change> changes = apply(gateway, list);
                if (failing.remove(gateway.deviceId())) {
                    log.info("Gateway {} ({}) is read again", gateway.id(), gateway.name());
                }
                return changes;
            })
            .doOnError(e -> {
                if (failing.add(gateway.deviceId())) {
                    log.warn("Gateway {} ({}) could not be read, its garage sensors are not known once it has been "
                        + "silent for {}: {}", gateway.id(), gateway.name(), properties.gatewaySilentAfter(), e.toString());
                }
            })
            .onErrorComplete()
            //the changes stored one after the other, each never failing: a write never stops the read of the next gateway
            .flatMapMany(Flux::fromIterable)
            .concatMap(this::storeChange)
            .then();
    }

    //the stored readings of the sensors no gate names go with them, once the gates name others; never fails. Gates that
    //name none keep them: one empty answer must not wipe what a restart compares with
    private Mono<Void> forgetTheGone(final Set<Long> named) {
        return Mono.just(named)
            .filter(ids -> !ids.isEmpty() && !ids.equals(trimmedTo.getAndSet(ids)))
            .flatMap(store::retain);
    }

    /** A reading of a sensor that changed, to be stored. */
    record Change(long sensor, Reading reading) {
    }

    private Mono<Void> storeChange(final Change change) {
        return store.save(change.sensor(), change.reading());
    }

    //the window components of the gateway that belong to a sensor of a garage gate, each with what it holds now; a
    //component of another device, or of no window, is not this service's. Answers the readings that changed
    List<Change> apply(final Gateway gateway, final List<ShellyComponent> list) {
        final Map<String, Sensor> byAddress = sensors.get().values().stream()
            .filter(sensor -> sensor.address() != null)
            .collect(Collectors.toMap(sensor -> sensor.address().toLowerCase(Locale.ROOT), sensor -> sensor, (a, b) -> a));
        final LocalDateTime now = now();
        final Map<SensorComponent, Long> found = new HashMap<>();
        final List<Change> changes = new ArrayList<>();

        list.stream()
            .filter(component -> component.getKey() != null && component.getKey().startsWith(SENSOR))
            .forEach(component -> as(component.getConfig(), BthomeSensorConfig.class)
                .filter(config -> config.getId() != null && Integer.valueOf(WINDOW_OBJECT).equals(config.getObjId()))
                .flatMap(config -> Optional.ofNullable(config.getAddr())
                    .map(address -> byAddress.get(address.toLowerCase(Locale.ROOT)))
                    .map(sensor -> {
                        found.put(new SensorComponent(gateway.deviceId(), config.getId()), sensor.id());
                        return sensor;
                    }))
                .ifPresent(sensor -> as(component.getStatus(), BthomeSensorStatus.class)
                    .ifPresentOrElse(
                        status -> report(gateway.deviceId(), sensor.id(), status, now).ifPresent(changes::add),
                        () -> confirm(sensor.id(), now))));

        components.keySet().removeIf(key -> key.gateway().equals(gateway.deviceId()) && !found.containsKey(key));
        components.putAll(found);
        return changes;
    }

    //is this status topic the window object of a sensor of a garage gate - asked before a body is read
    public boolean knows(final String gatewayId, final Integer component) {
        return gatewayId != null && component != null
            && components.containsKey(new SensorComponent(gatewayId.toLowerCase(Locale.ROOT), component));
    }

    //the reading is taken at once; what the answer stores runs when it is subscribed to, and never fails - a failed
    //write never stops the listener
    public Mono<Void> observe(final String gatewayId, final int component, final BthomeSensorStatus status) {
        final String gateway = gatewayId.toLowerCase(Locale.ROOT);
        return Optional.ofNullable(components.get(new SensorComponent(gateway, component)))
            .flatMap(sensor -> report(gateway, sensor, status, now()))
            .map(this::storeChange)
            .orElseGet(Mono::empty);
    }

    //A value that is not a truth is not a state: the gateway stood for the sensor all the same. Of two reports the one
    //the gateway heard later counts - a read of the gateway may bring an older state than a publish a moment before it,
    //and a gateway restarted may bring back a state older than the one stored before the restart.
    //Answers the reading to store: a new value, or the same value heard later - never a confirmation
    private Optional<Change> report(final String gateway, final long sensor, final BthomeSensorStatus status,
                                    final LocalDateTime now) {
        final Boolean open = status.getValue() instanceof Boolean value ? value : null;
        final Instant updatedAt = status.getLastUpdatedTs() == null ? null : Instant.ofEpochSecond(status.getLastUpdatedTs());
        final AtomicReference<Change> changed = new AtomicReference<>();
        readings.compute(sensor, (id, held) -> {
            if (open == null) {
                return held == null ? new Reading(null, null, now) : confirmedIfHeard(held, now);
            }
            if (held != null && held.updatedAt() != null && updatedAt != null && updatedAt.isBefore(held.updatedAt())) {
                tellOlder(gateway, sensor, open, updatedAt, held);
                return new Reading(held.open(), held.updatedAt(), now);
            }
            final Reading next = next(sensor, held, open, updatedAt, now);
            if (isChange(held, next)) {
                changed.set(new Change(sensor, next));
            }
            return next;
        });
        return Optional.ofNullable(changed.get());
    }

    //a value not older than the one held; another value than the held one is logged
    private Reading next(final long sensor, final Reading held, final boolean open, final Instant updatedAt,
                         final LocalDateTime now) {
        if (held == null || !Boolean.valueOf(open).equals(held.open())) {
            log.info("Garage sensor {} ({}): {}", sensor, nameOf(sensor), word(open));
        }
        return new Reading(open, updatedAt, now);
    }

    //a new value, or the same value heard later - never a confirmation of what is held
    private static boolean isChange(final Reading held, final Reading next) {
        return held == null || !Objects.equals(held.open(), next.open())
            || next.updatedAt() != null && !next.updatedAt().equals(held.updatedAt());
    }

    //once per sensor and start, so that the next restored state of a gateway shows in the logs
    private void tellOlder(final String gateway, final long sensor, final boolean open, final Instant updatedAt,
                           final Reading held) {
        if (olderTold.add(sensor)) {
            log.info("Garage sensor {} ({}): gateway {} gave {} heard at {}, older than the kept {} heard at {} - the kept "
                + "one stays", sensor, nameOf(sensor), gateway, word(open), heardAt(updatedAt), word(held.open()),
                heardAt(held.updatedAt()));
        }
    }

    private static String word(final Boolean open) {
        return Boolean.TRUE.equals(open) ? "open" : "closed";
    }

    private LocalDateTime heardAt(final Instant instant) {
        return LocalDateTime.ofInstant(instant, clock.getZone());
    }

    //a component without a window value: the gateway stands for what it heard before, but a reading read back from the
    //table it never stood for stays not known - only a value of the gateway confirms that one
    private void confirm(final long sensor, final LocalDateTime now) {
        readings.compute(sensor, (id, held) -> held == null ? new Reading(null, null, now) : confirmedIfHeard(held, now));
    }

    private static Reading confirmedIfHeard(final Reading held, final LocalDateTime now) {
        return held.confirmedAt() == null ? held : new Reading(held.open(), held.updatedAt(), now);
    }

    public GarageReading stateOf(final GarageGate gate) {
        return stateOf(gate, now());
    }

    //open when one sensor is open (since the earliest of those heard open), not known when one is not known, closed when
    //every one is closed (since the last of them heard closed)
    public GarageReading stateOf(final GarageGate gate, final LocalDateTime now) {
        final LocalDateTime oldest = now.minus(properties.gatewaySilentAfter());
        final List<Reading> known = gate.sensors().stream()
            .map(readings::get)
            .toList();
        final boolean unknown = known.stream().anyMatch(reading -> reading == null || reading.open() == null
            || reading.confirmedAt() == null || reading.confirmedAt().isBefore(oldest));
        final LocalDateTime confirmedAt = known.stream()
            .filter(Objects::nonNull)
            .map(Reading::confirmedAt)
            .filter(Objects::nonNull)
            .min(Comparator.naturalOrder())
            .orElse(null);
        final List<Reading> open = known.stream()
            .filter(reading -> reading != null && Boolean.TRUE.equals(reading.open()) && reading.confirmedAt() != null
                && !reading.confirmedAt().isBefore(oldest))
            .toList();
        if (!open.isEmpty()) {
            return new GarageReading(GarageState.OPEN, sinceOf(open.stream().map(Reading::updatedAt)
                .filter(Objects::nonNull).min(Comparator.naturalOrder())), confirmedAt);
        }
        if (unknown) {
            return new GarageReading(GarageState.UNKNOWN, null, confirmedAt);
        }
        return new GarageReading(GarageState.CLOSED, sinceOf(known.stream().map(Reading::updatedAt)
            .filter(Objects::nonNull).max(Comparator.naturalOrder())), confirmedAt);
    }

    private LocalDateTime sinceOf(final Optional<Instant> heard) {
        return heard.map(this::heardAt).orElse(null);
    }

    private String nameOf(final long sensor) {
        final Sensor known = sensors.get().get(sensor);
        return known == null ? "?" : known.name();
    }

    //the opening sensors of the registry the garage gates name; a switched-off one is not read (the registry answers the
    //enabled ones only), so its gate is not known
    private static Map<Long, Sensor> toSensors(final List<SmartDevice> devices, final Set<Long> named) {
        final Map<Long, Sensor> read = new HashMap<>();
        devices.stream()
            .filter(Objects::nonNull)
            .filter(device -> device.getId() != null && named.contains(device.getId()))
            .forEach(device -> read.put(device.getId(), new Sensor(
                device.getId(),
                device.getName(),
                device.getBthome() == null ? null : device.getBthome().getAddress())));
        return Map.copyOf(read);
    }

    //a gateway is a Shelly on the network with its MQTT id; one without either cannot be read
    private static List<Gateway> toGateways(final List<SmartDevice> devices) {
        return devices.stream()
            .filter(Objects::nonNull)
            .filter(device -> {
                final boolean usable = device.getId() != null && device.getShelly() != null
                    && device.getShelly().getAddress() != null && device.getShelly().getDeviceId() != null;
                if (!usable) {
                    log.warn("BTHome gateway {} ({}) of the registry has no address or MQTT id and is not read",
                        device.getId(), device.getName());
                }
                return usable;
            })
            .map(device -> new Gateway(device.getId(), device.getName(), device.getShelly().getAddress(),
                device.getShelly().getDeviceId().toLowerCase(Locale.ROOT)))
            .toList();
    }

    private <T> Optional<T> as(final Map<String, Object> raw, final Class<T> model) {
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(jsonMapper.convertValue(raw, model));
        } catch (JacksonException | IllegalArgumentException notTheModel) {
            return Optional.empty();
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
