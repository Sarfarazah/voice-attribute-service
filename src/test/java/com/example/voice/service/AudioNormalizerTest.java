package com.example.voice.service;

import com.example.voice.config.VoiceProperties;
import com.example.voice.exception.ApiException;
import com.example.voice.model.AudioQuality;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.*;

class AudioNormalizerTest {
    @Test
    void emptyAudioIsRejected() {
        var n = new AudioNormalizer(new VoiceProperties(1024, 60, 1, 1000, .6, .4, "http://x"));
        var e = assertThrows(ApiException.class, () -> n.normalize(new ByteArrayInputStream(new byte[0]), 0));
        assertEquals("EMPTY_AUDIO", e.code());
    }

    @Test
    void corruptAudioIsRejected() {
        var n = new AudioNormalizer(new VoiceProperties(1024, 60, 1, 1000, .6, .4, "http://x"));
        assertThrows(ApiException.class, () -> n.normalize(new ByteArrayInputStream(new byte[]{3, 5, 7}), 3));
    }

    @Test
    void deletesTemporaryFile() throws Exception {
        var n = new AudioNormalizer(new VoiceProperties(1024, 60, 1, 1000, .6, .4, "http://x"));
        var file = java.nio.file.Files.createTempFile("voice-cleanup-", ".wav");
        n.delete(file);
        assertFalse(java.nio.file.Files.exists(file));
    }

    @Test
    void qualityGateClassifiesSilentShortAndUsableAudio() {
        var props = new VoiceProperties(1024, 60, 1, 1000, .6, .4, "http://x");
        assertEquals(AudioQuality.insufficient, AudioQualityAssessor.assess(3, 0.0001, 0.01, 0, props));
        assertEquals(AudioQuality.insufficient, AudioQualityAssessor.assess(0.2, 0.1, 0.9, 0, props));
        assertEquals(AudioQuality.good, AudioQualityAssessor.assess(3, 0.05, 0.8, 0, props));
        assertEquals(AudioQuality.degraded, AudioQualityAssessor.assess(3, 0.05, 0.8, 0.02, props));
    }
}
