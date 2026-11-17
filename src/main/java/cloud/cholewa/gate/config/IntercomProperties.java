package cloud.cholewa.gate.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.ZoneId;

//The door station as the bridge names it: device - the name of the doorbell in DOORBELLS of the bridge (the third part
//of its topics, "gate"); bridgeZone - the zone of the wall time the bridge writes into last_unlocked (its container runs
//in UTC - 15:13:37 was 16:13:37 of the house, deployment-tools/scripts/hikvision/devices.md)
@Validated
@ConfigurationProperties("intercom")
public record IntercomProperties(
    @NotBlank String device,
    @NotNull ZoneId bridgeZone
) {
}
