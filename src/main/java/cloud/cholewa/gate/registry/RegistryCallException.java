package cloud.cholewa.gate.registry;

//a read of database-service that did not end with the list asked for - the gate of the device registry or the household
//profiles; never answered as such: a failed refresh keeps the gate it had (a WARN), a failed read of the profiles is
//REGISTRY_UNAVAILABLE of an opening from the dashboard
public class RegistryCallException extends RuntimeException {

    public RegistryCallException(final String message) {
        super(message);
    }
}
