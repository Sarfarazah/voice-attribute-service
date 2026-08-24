package com.example.voice.config;

import com.example.voice.client.InferenceClient;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class InferenceHealthIndicator implements HealthIndicator {
    private final InferenceClient client;

    public InferenceHealthIndicator(InferenceClient c) {
        client = c;
    }

    public Health health() {
        return client.healthy() ? Health.up().build() : Health.down().withDetail("inference", "unavailable").build();
    }
}
