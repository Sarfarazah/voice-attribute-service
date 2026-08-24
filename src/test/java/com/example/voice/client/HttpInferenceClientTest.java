package com.example.voice.client;

import com.example.voice.config.VoiceProperties;
import com.example.voice.exception.ApiException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

class HttpInferenceClientTest {
    private final HttpInferenceClient client = new HttpInferenceClient(
            new VoiceProperties(1000, 60, 1, 1000, .6, .45, "http://localhost:9999"), new ObjectMapper());

    @Test void parsesStructuredInferenceJson() {
        var response = client.parseResponse("{\"age\":42.3,\"female\":0.12,\"male\":0.83,\"child\":0.05}");
        assertEquals(42.3, response.age());
        assertEquals(.83, response.male());
    }

    @Test void rejectsMalformedOrInvalidInferenceJson() {
        ApiException malformed = assertThrows(ApiException.class, () -> client.parseResponse("not-json"));
        assertEquals("INFERENCE_INVALID_RESPONSE", malformed.code());
        assertThrows(ApiException.class, () -> client.parseResponse("{\"age\":42,\"female\":.9,\"male\":.9,\"child\":0}"));
        assertThrows(ApiException.class, () -> client.parseResponse("{\"age\":42,\"female\":.1,\"male\":.9}"));
    }
}
