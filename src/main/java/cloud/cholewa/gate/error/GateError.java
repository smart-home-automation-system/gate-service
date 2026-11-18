package cloud.cholewa.gate.error;

import cloud.cholewa.commons.error.model.ErrorId;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

//The causes of a request the API refuses. Their names are the codes of the answer (ErrorId.codeOf) - wire contract for
//the dashboard (HAS-454), pinned by GateErrorTest
@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public enum GateError implements ErrorId {

    //the member who opens the gate is no switched-on member of the household registry
    UNKNOWN_MEMBER("Unknown household member"),

    //an opening within gate.open.ignore-repeat-for of the one before - the gate is step-by-step
    OPEN_TOO_SOON("Open too soon"),

    //the Shelly of the gate did not take the command - it may have been carried out all the same
    DEVICE_FAILED("Device failed"),

    //no gate to command: none in the registry, the registry never read, or a relay that does not switch itself off
    GATE_UNAVAILABLE("Gate unavailable"),

    //the household profiles could not be read, so the member cannot be checked
    REGISTRY_UNAVAILABLE("Registry unavailable"),

    //the period of the events (HAS-450): from not before to, or longer than 31 days
    INVALID_RANGE("Invalid range"),

    //no garage gate of that id (HAS-473): not in the registry, switched off, or the registry never read
    NOT_FOUND_GATE("Gate not found");

    private final String description;
}
