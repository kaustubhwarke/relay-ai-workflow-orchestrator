package com.relay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Relay: AI workflow orchestrator.
 *
 * <p>The same artifact runs as API + worker (default), API only, or worker only; see
 * {@code relay.worker.enabled} and the {@code worker} profile.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class RelayApplication {

    public static void main(String[] args) {
        SpringApplication.run(RelayApplication.class, args);
    }
}
