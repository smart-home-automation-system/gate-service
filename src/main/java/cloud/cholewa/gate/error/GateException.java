package cloud.cholewa.gate.error;

import lombok.Getter;

//A request the API refuses, by its cause; one subclass per cause, so each has its own DomainExceptionProcessor of
//cholewa-commons with its status (ExceptionHandlerConfig). The message is answered as details for a 4xx
@Getter
public abstract class GateException extends RuntimeException {

    private final GateError error;

    protected GateException(final GateError error, final String message) {
        super(message);
        this.error = error;
    }

    public static final class UnknownMember extends GateException {
        public UnknownMember(final String member) {
            super(GateError.UNKNOWN_MEMBER, "No switched-on household member is named " + member);
        }
    }

    public static final class OpenTooSoon extends GateException {
        public OpenTooSoon(final long seconds) {
            super(GateError.OPEN_TOO_SOON, "The gate was opened less than " + seconds + " s ago");
        }
    }

    public static final class DeviceFailed extends GateException {
        public DeviceFailed(final String reason) {
            super(GateError.DEVICE_FAILED, "The Shelly of the gate did not take the command: " + reason);
        }
    }

    public static final class GateUnavailable extends GateException {
        public GateUnavailable(final String reason) {
            super(GateError.GATE_UNAVAILABLE, reason);
        }
    }

    public static final class InvalidRange extends GateException {
        public InvalidRange(final String reason) {
            super(GateError.INVALID_RANGE, reason);
        }
    }

    public static final class RegistryUnavailable extends GateException {
        public RegistryUnavailable() {
            super(GateError.REGISTRY_UNAVAILABLE, "The household profiles could not be read");
        }
    }
}
