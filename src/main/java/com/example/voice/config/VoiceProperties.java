package com.example.voice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "voice")
public record VoiceProperties(long maxFileBytes, double maxAudioDurationSeconds, double minAudioDurationSeconds,
                              int inferenceTimeoutMs, double genderConfidenceThreshold,
                              double ageConfidenceThreshold, String inferenceServiceUrl) {
}
