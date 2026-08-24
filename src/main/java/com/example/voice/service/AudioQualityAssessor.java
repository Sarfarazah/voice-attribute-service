package com.example.voice.service;

import com.example.voice.config.VoiceProperties;
import com.example.voice.model.AudioQuality;

/** Small, explainable quality gate for normalized mono PCM audio. */
final class AudioQualityAssessor {
    private AudioQualityAssessor() { }

    static AudioQuality assess(double durationSeconds, double rms, double nonSilentRatio, double clipRatio,
                               VoiceProperties properties) {
        if (durationSeconds < properties.minAudioDurationSeconds() || rms < 0.003 || nonSilentRatio < 0.15) {
            return AudioQuality.insufficient;
        }
        if (clipRatio > 0.01 || nonSilentRatio < 0.45 || rms < 0.01) {
            return AudioQuality.degraded;
        }
        return AudioQuality.good;
    }
}
