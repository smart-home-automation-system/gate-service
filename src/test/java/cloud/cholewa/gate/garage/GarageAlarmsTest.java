package cloud.cholewa.gate.garage;

import cloud.cholewa.gate.config.GarageAlarmProperties;
import cloud.cholewa.gate.database.GarageGateAlertEntity;
import cloud.cholewa.gate.database.GarageGateAlertRepository;
import cloud.cholewa.gate.garage.PresenceClient.ResidentPresence;
import cloud.cholewa.gate.garage.WeatherClient.Temperature;
import cloud.cholewa.gate.gate.GarageGate;
import cloud.cholewa.gate.gate.GateRegistry;
import cloud.cholewa.gate.household.HouseholdNotifier;
import cloud.cholewa.home.model.HouseholdEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//HAS-475: one pass of the alarms - what the evaluator says, told and written; nothing read that fails stops it
class GarageAlarmsTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    //19:15 of the house
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-18T18:15:00Z"), HOUSE);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 18, 19, 15);
    private static final LocalDateTime OPENED = NOW.minusMinutes(15);
    private static final GarageGate KATE = new GarageGate(148, "Kate", "192.0.2.79", 0, List.of(69L));

    private final GateRegistry gateRegistry = mock(GateRegistry.class);
    private final GarageSensors garageSensors = mock(GarageSensors.class);
    private final WeatherClient weatherClient = mock(WeatherClient.class);
    private final PresenceClient presenceClient = mock(PresenceClient.class);
    private final GarageGateAlertRepository repository = mock(GarageGateAlertRepository.class);
    private final HouseholdNotifier notifier = mock(HouseholdNotifier.class);
    private final GarageAlarms alarms = new GarageAlarms(gateRegistry, garageSensors, weatherClient, presenceClient,
        repository, notifier, new GarageAlarmProperties(Duration.ofMinutes(15), 15.0, Duration.ofMinutes(5),
        Duration.ofMinutes(30), Duration.ofMinutes(30), Duration.ofHours(1)), CLOCK);

    @BeforeEach
    void setUp() {
        when(gateRegistry.garages()).thenReturn(List.of(new GateRegistry.GarageGateState(KATE, 1.0, true, NOW)));
        when(garageSensors.stateOf(KATE, NOW)).thenReturn(new GarageSensors.GarageReading(GarageState.OPEN, OPENED, NOW));
        when(weatherClient.outside()).thenReturn(Mono.just(new Temperature(7.5, false)));
        when(presenceClient.residents()).thenReturn(Mono.just(List.of(new ResidentPresence("Anna", true, null, NOW))));
        when(repository.findByClosedAtIsNull()).thenReturn(Flux.empty());
        when(repository.save(any())).thenAnswer(saved -> Mono.just(saved.getArgument(0)));
        when(notifier.garageGate(any(), any(), any(), anyInt(), any(), any())).thenReturn(Mono.just(true));
    }

    @Test
    void should_tell_the_cold_alarm_with_the_temperature_and_write_the_episode() {
        alarms.judgeOnce().as(StepVerifier::create).verifyComplete();

        verify(notifier).garageGate(HouseholdEvent.GARAGE_GATE_OPEN_COLD, KATE, OPENED, 0, 7.5, NOW);
        verify(repository).save(GarageGateAlertEntity.raised(148L, GarageAlarmEvaluator.COLD, OPENED, NOW));
    }

    //a broker away: nothing written, so the next pass tells it again
    @Test
    void should_write_nothing_of_an_alarm_that_could_not_be_told() {
        when(notifier.garageGate(any(), any(), any(), anyInt(), any(), any())).thenReturn(Mono.just(false));

        alarms.judgeOnce().as(StepVerifier::create).verifyComplete();

        verify(repository, never()).save(any());
    }

    @Test
    void should_tell_the_gate_closed_and_end_its_episodes() {
        final GarageGateAlertEntity cold = new GarageGateAlertEntity(7L, 148L, GarageAlarmEvaluator.COLD, OPENED,
            OPENED.plusMinutes(15), OPENED.plusMinutes(15), 0, null);
        when(repository.findByClosedAtIsNull()).thenReturn(Flux.just(cold));
        when(garageSensors.stateOf(KATE, NOW)).thenReturn(new GarageSensors.GarageReading(GarageState.CLOSED, NOW, NOW));

        alarms.judgeOnce().as(StepVerifier::create).verifyComplete();

        verify(notifier).garageGate(HouseholdEvent.GARAGE_GATE_CLOSED, KATE, OPENED, 0, null, NOW);
        verify(repository).save(cold.closed(NOW));
    }

    //a weather-service and a presence-service away are no cold and no empty house: the warning still comes after 30 min
    @Test
    void should_go_on_without_the_weather_and_the_presence() {
        when(weatherClient.outside()).thenReturn(Mono.error(new IllegalStateException("weather-service answered 503")));
        when(presenceClient.residents()).thenReturn(Mono.error(new IllegalStateException("timeout")));
        when(garageSensors.stateOf(KATE, NOW))
            .thenReturn(new GarageSensors.GarageReading(GarageState.OPEN, NOW.minusMinutes(30), NOW));

        alarms.judgeOnce().as(StepVerifier::create).verifyComplete();

        final ArgumentCaptor<HouseholdEvent> event = ArgumentCaptor.forClass(HouseholdEvent.class);
        verify(notifier).garageGate(event.capture(), eq(KATE), any(), eq(0), any(), eq(NOW));
        assertThat(event.getValue()).isEqualTo(HouseholdEvent.GARAGE_GATE_OPEN_LONG);
    }

    //a repeat counts on the episode
    @Test
    void should_repeat_and_count_the_repeat() {
        final GarageGateAlertEntity cold = new GarageGateAlertEntity(7L, 148L, GarageAlarmEvaluator.COLD,
            NOW.minusMinutes(45), NOW.minusMinutes(30), NOW.minusMinutes(30), 0, null);
        when(repository.findByClosedAtIsNull()).thenReturn(Flux.just(cold));
        when(garageSensors.stateOf(KATE, NOW))
            .thenReturn(new GarageSensors.GarageReading(GarageState.OPEN, NOW.minusMinutes(45), NOW));

        alarms.judgeOnce().as(StepVerifier::create).verifyComplete();

        verify(notifier).garageGate(HouseholdEvent.GARAGE_GATE_OPEN_COLD, KATE, NOW.minusMinutes(45), 1, 7.5, NOW);
        verify(repository).save(cold.reminded(NOW));
    }

    @Test
    void should_read_nothing_without_a_garage_gate() {
        when(gateRegistry.garages()).thenReturn(List.of());

        alarms.judgeOnce().as(StepVerifier::create).verifyComplete();

        verify(repository, never()).findByClosedAtIsNull();
    }
}
