package com.chitfund.batch.chit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/** Wires {@link ChitServiceClient} from {@code chit.service.*} in application.yml (environment variables CHIT_SERVICE_*). */
@Configuration
public class ChitServiceConfig {

    @Bean
    public ChitServiceClient chitServiceClient(
            ObjectMapper objectMapper,
            @Value("${chit.service.base-url:}") String baseUrl,
            @Value("${chit.service.username:}") String username,
            @Value("${chit.service.password:}") String password,
            @Value("${chit.service.connect-timeout-seconds:5}") long connectTimeoutSeconds,
            @Value("${chit.service.request-timeout-seconds:240}") long requestTimeoutSeconds,
            @Value("${chit.service.max-attempts:4}") int maxAttempts,
            @Value("${chit.service.retry-backoff-millis:500}") long retryBackoffMillis) {
        return new ChitServiceClient(new ChitServiceSettings(baseUrl, username, password,
                Duration.ofSeconds(connectTimeoutSeconds), Duration.ofSeconds(requestTimeoutSeconds),
                maxAttempts, Duration.ofMillis(retryBackoffMillis)), objectMapper);
    }
}
