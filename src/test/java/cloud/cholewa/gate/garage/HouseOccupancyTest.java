package cloud.cholewa.gate.garage;

import cloud.cholewa.gate.garage.PresenceClient.ResidentPresence;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

//HAS-475: empty only when every member is away by a fresh reading - unknown is never empty
class HouseOccupancyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 18, 19, 15);

    private static ResidentPresence away(final String name, final LocalDateTime since, final LocalDateTime checked) {
        return new ResidentPresence(name, false, since, checked);
    }

    @Test
    void should_be_empty_since_the_last_to_go() {
        final HouseOccupancy house = HouseOccupancy.judge(List.of(
            away("Anna", NOW.minusHours(2), NOW), away("Jan", NOW.minusMinutes(10), NOW)), NOW);

        assertThat(house).isEqualTo(new HouseOccupancy(HouseOccupancy.State.EMPTY, NOW.minusMinutes(10)));
        assertThat(house.empty()).isTrue();
    }

    @Test
    void should_be_occupied_while_one_is_at_home() {
        assertThat(HouseOccupancy.judge(List.of(away("Anna", NOW, NOW),
            new ResidentPresence("Jan", true, NOW, NOW)), NOW).state()).isEqualTo(HouseOccupancy.State.OCCUPIED);
    }

    //a stale reading, a member never observed, nobody listed or a failed read: not known, never empty
    @Test
    void should_not_know_the_house_without_fresh_readings_of_everybody() {
        assertThat(HouseOccupancy.judge(List.of(away("Anna", NOW, NOW.minusMinutes(6))), NOW).empty()).isFalse();
        assertThat(HouseOccupancy.judge(List.of(new ResidentPresence("Anna", null, null, null)), NOW).empty()).isFalse();
        assertThat(HouseOccupancy.judge(List.of(), NOW).state()).isEqualTo(HouseOccupancy.State.UNKNOWN);
        assertThat(HouseOccupancy.judge(null, NOW).state()).isEqualTo(HouseOccupancy.State.UNKNOWN);
        //everybody away, one without the time they went: empty since a time not known
        assertThat(HouseOccupancy.judge(List.of(away("Anna", null, NOW)), NOW).emptySince()).isNull();
    }
}
