package com.example.voice.config;

import com.example.voice.client.InferenceClient;
import com.example.voice.dto.InferenceResponse;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class InferenceHealthIndicatorTest {
    @Test
    void healthIsUpWhenWorkerIsHealthy() {
        InferenceClient c = new InferenceClient() {
            public InferenceResponse infer(Path p) {
                return null;
            }

            public boolean healthy() {
                return true;
            }
        };
        assertEquals("UP", new InferenceHealthIndicator(c).health().getStatus().getCode());
    }
}
