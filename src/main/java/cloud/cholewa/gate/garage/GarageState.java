package cloud.cholewa.gate.garage;

import com.fasterxml.jackson.annotation.JsonValue;

//What the sensors of a garage gate say (HAS-473): open when one reports open, closed when every one reports closed, not
//known when one never reported or its gateway went silent - a failing sensor never passes for a closed gate. The JSON
//values are wire contract for the dashboard (HAS-454)
public enum GarageState {
    OPEN("open"),
    CLOSED("closed"),
    UNKNOWN("unknown");

    private final String value;

    GarageState(final String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
