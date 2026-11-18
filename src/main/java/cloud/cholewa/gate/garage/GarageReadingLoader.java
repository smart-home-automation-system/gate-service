package cloud.cholewa.gate.garage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Reads the stored readings of the garage sensors back before anything reads a gateway (HAS-473, the copy of
 * WindowReadingLoader of blinds-service): the scheduled read of the sensors (registered once the context is refreshed)
 * and the device listener (phase {@code Integer.MAX_VALUE}) both start after a lifecycle of phase 0. Not a
 * {@code CommandLineRunner}: a runner runs after them.
 * <p>
 * <b>Never fatal</b>: a stored reading is only what a gateway value is compared with. {@link GarageSensors#restore()}
 * never fails; the wait is bounded.
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class GarageReadingLoader implements SmartLifecycle {

    private static final int PHASE = 0;
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(15);

    private final GarageSensors garageSensors;

    private volatile boolean running;

    @Override
    public void start() {
        //blocking on purpose, bounded: one query, before the first read of a gateway; a hung one ends at the bound
        garageSensors.restore()
            .timeout(STARTUP_TIMEOUT)
            .doOnError(e -> log.warn("The stored garage sensor readings were not read back in {}, the start knows none: {}",
                STARTUP_TIMEOUT, e.toString()))
            .onErrorComplete()
            .block();
        running = true;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
