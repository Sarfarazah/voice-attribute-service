package com.example.voice.controller;

import com.example.voice.dto.*;
import com.example.voice.exception.GlobalExceptionHandler;
import com.example.voice.model.*;
import com.example.voice.service.AnalysisService;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AnalyzeControllerTest {
    MockMvc mvc;
    AnalysisService service = mock(AnalysisService.class);

    @BeforeEach
    void init() {
        mvc = MockMvcBuilders.standaloneSetup(new AnalyzeController(service)).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    private final UUID id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @Test
    void validAudioReturns200() throws Exception {
        when(service.analyze(eq(id), any(), anyLong())).thenReturn(new AnalyzeResponse(id, new PredictionDto<>(GenderPrediction.male, .8), new PredictionDto<>(AgeBracket.AGE_31_45, .6), 12, AudioQuality.good));
        mvc.perform(multipart("/analyze").file(new MockMultipartFile("audio", "x.wav", "audio/wav", new byte[]{1, 2})).param("contact_id", id.toString())).andExpect(status().isOk()).andExpect(jsonPath("$.gender.prediction").value("male")).andExpect(jsonPath("$.age_bracket.prediction").value("31-45"));
    }

    @Test
    void missingContactIdReturns400() throws Exception {
        mvc.perform(multipart("/analyze").file(new MockMultipartFile("audio", "x.wav", "audio/wav", new byte[]{1}))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void missingAudioReturns400() throws Exception {
        mvc.perform(multipart("/analyze").param("contact_id", id.toString())).andExpect(status().isBadRequest());
    }

    @Test
    void unsupportedMediaTypeReturns415() throws Exception {
        mvc.perform(post("/analyze?contact_id=" + id).contentType("text/plain").content("not audio"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.error").value("UNSUPPORTED_MEDIA_TYPE"));
    }
}
