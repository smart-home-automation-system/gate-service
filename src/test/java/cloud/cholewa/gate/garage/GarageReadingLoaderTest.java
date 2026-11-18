package cloud.cholewa.gate.garage;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//HAS-473: the stored readings read back before the first read of a gateway - in phase 0, never fatal
class GarageReadingLoaderTest {

    private final GarageSensors garageSensors = mock(GarageSensors.class);
    private final GarageReadingLoader loader = new GarageReadingLoader(garageSensors);

    @Test
    void should_restore_the_readings_before_the_listener_and_the_schedules() {
        when(garageSensors.restore()).thenReturn(Mono.empty());

        loader.start();

        verify(garageSensors).restore();
        assertThat(loader.isRunning()).isTrue();
        assertThat(loader.getPhase()).isZero();
        loader.stop();
        assertThat(loader.isRunning()).isFalse();
    }

    //a read that fails, or hangs past the bound, leaves the start without readings - never without the service
    @Test
    void should_start_when_the_readings_cannot_be_read() {
        when(garageSensors.restore()).thenReturn(Mono.error(new IllegalStateException("down")));

        loader.start();

        assertThat(loader.isRunning()).isTrue();
    }
}
