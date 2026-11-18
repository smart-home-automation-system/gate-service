package cloud.cholewa.gate.config;

import cloud.cholewa.commons.error.GlobalErrorExceptionHandler;
import cloud.cholewa.commons.error.processor.DomainExceptionProcessor;
import cloud.cholewa.gate.error.GateError;
import cloud.cholewa.gate.error.GateException;
import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.boot.webflux.error.ErrorAttributes;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.codec.ServerCodecConfigurer;

import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

//The error contract of the organization (cholewa-commons) with one processor per cause of a refused request (HAS-449, HAS-450,
//HAS-473)
@Configuration
public class ExceptionHandlerConfig {

    @Bean
    @Order(-2)
    public GlobalErrorExceptionHandler globalErrorExceptionHandler(
        final ErrorAttributes errorAttributes,
        final WebProperties webProperties,
        final ApplicationContext applicationContext,
        final ServerCodecConfigurer serverCodecConfigurer
    ) {
        return new GlobalErrorExceptionHandler(
            errorAttributes, webProperties.getResources(), applicationContext, serverCodecConfigurer
        ).withCustomErrorProcessor(Map.of(
            GateException.UnknownMember.class, new DomainExceptionProcessor(BAD_REQUEST, GateError.UNKNOWN_MEMBER),
            GateException.OpenTooSoon.class, new DomainExceptionProcessor(CONFLICT, GateError.OPEN_TOO_SOON),
            GateException.DeviceFailed.class, new DomainExceptionProcessor(BAD_GATEWAY, GateError.DEVICE_FAILED),
            GateException.GateUnavailable.class,
            new DomainExceptionProcessor(SERVICE_UNAVAILABLE, GateError.GATE_UNAVAILABLE),
            GateException.RegistryUnavailable.class,
            new DomainExceptionProcessor(BAD_GATEWAY, GateError.REGISTRY_UNAVAILABLE),
            GateException.InvalidRange.class, new DomainExceptionProcessor(BAD_REQUEST, GateError.INVALID_RANGE),
            GateException.GateNotFound.class, new DomainExceptionProcessor(NOT_FOUND, GateError.NOT_FOUND_GATE)
        ));
    }
}
