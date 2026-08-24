package com.example.voice.service;

import com.example.voice.client.InferenceClient;
import com.example.voice.config.VoiceProperties;
import com.example.voice.dto.InferenceResponse;
import com.example.voice.exception.ApiException;
import com.example.voice.model.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalysisServiceTest {
    AudioNormalizer normalizer = mock(AudioNormalizer.class);
    InferenceClient inference = mock(InferenceClient.class);
    VoiceProperties props = new VoiceProperties(1000, 60, 1, 1000, .6, .45, "http://x");
    AnalysisService service = new AnalysisService(normalizer, inference, props);
    Path temp;

    @BeforeEach
    void setup() throws IOException {
        temp = Files.createTempFile("test-audio-", ".wav");
        when(normalizer.normalize(any(), anyLong())).thenReturn(new NormalizedAudio(temp, 3, AudioQuality.good));
    }

    @AfterEach
    void clean() throws IOException {
        Files.deleteIfExists(temp);
    }

    @Test
    void confidenceIsBoundedAndLowGenderIsUnknown() {
        when(inference.infer(temp)).thenReturn(new InferenceResponse(.33, .51, .49, 0));
        var r = service.analyze(UUID.randomUUID(), new ByteArrayInputStream(new byte[]{1}), 1);
        assertEquals(GenderPrediction.unknown, r.gender().prediction());
        assertTrue(r.gender().confidence() >= 0 && r.gender().confidence() <= 1);
        assertTrue(r.age_bracket().confidence() >= 0 && r.age_bracket().confidence() <= 1);
    }

    @Test
    void insufficientAudioSkipsInference() {
        when(normalizer.normalize(any(), anyLong())).thenReturn(new NormalizedAudio(temp, .2, AudioQuality.insufficient));
        var r = service.analyze(UUID.randomUUID(), new ByteArrayInputStream(new byte[0]), 0);
        assertEquals(AudioQuality.insufficient, r.audio_quality());
        assertEquals(GenderPrediction.unknown, r.gender().prediction());
        verifyNoInteractions(inference);
    }

    @Test
    void deletesTemporaryNormalizedAudioAfterInference() {
        when(inference.infer(temp)).thenReturn(new InferenceResponse(.5, .1, .9, 0));
        service.analyze(UUID.randomUUID(), new ByteArrayInputStream(new byte[]{1}), 1);
        verify(normalizer).delete(temp);
    }

    @Test
    void inferenceFailureIsPropagated() {
        when(inference.infer(temp)).thenThrow(new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "INFERENCE_UNAVAILABLE", "down"));
        assertThrows(ApiException.class, () -> service.analyze(UUID.randomUUID(), new ByteArrayInputStream(new byte[]{1}), 1));
        verify(normalizer).delete(temp);
    }

    @Test
    void mapsAdultAgeIntoRequiredBrackets() {
        assertEquals(AgeBracket.AGE_18_30, resultFor(25, .1, .8, .1).age_bracket().prediction());
        assertEquals(AgeBracket.AGE_31_45, resultFor(35, .1, .8, .1).age_bracket().prediction());
        assertEquals(AgeBracket.AGE_46_60, resultFor(50, .1, .8, .1).age_bracket().prediction());
        assertEquals(AgeBracket.AGE_60_PLUS, resultFor(65, .1, .8, .1).age_bracket().prediction());
    }

    @Test
    void mapsFemaleMaleChildAndLowConfidenceCorrectly() {
        assertEquals(GenderPrediction.female, resultFor(35, .8, .1, .1).gender().prediction());
        assertEquals(GenderPrediction.male, resultFor(35, .1, .8, .1).gender().prediction());
        assertEquals(GenderPrediction.unknown, resultFor(35, .1, .2, .7).gender().prediction());
        assertEquals(GenderPrediction.unknown, resultFor(35, .51, .49, 0).gender().prediction());
    }

    private com.example.voice.dto.AnalyzeResponse resultFor(double age, double female, double male, double child) {
        when(inference.infer(temp)).thenReturn(new InferenceResponse(age, female, male, child));
        return service.analyze(UUID.randomUUID(), new ByteArrayInputStream(new byte[]{1}), 1);
    }
}
