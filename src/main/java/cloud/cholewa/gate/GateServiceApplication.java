package cloud.cholewa.gate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class GateServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(GateServiceApplication.class, args);
    }

}
