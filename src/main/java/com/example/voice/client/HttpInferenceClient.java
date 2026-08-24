package com.example.voice.client;

import com.example.voice.config.VoiceProperties;
import com.example.voice.dto.InferenceResponse;
import com.example.voice.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

@Component
public class HttpInferenceClient implements InferenceClient {
    private final HttpClient http;
    private final VoiceProperties props;
    private final ObjectMapper objectMapper;

    public HttpInferenceClient(VoiceProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        // Uvicorn serves this internal endpoint over HTTP/1.1; avoid an h2c
        // upgrade attempt that it rejects before reading the request body.
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).version(HttpClient.Version.HTTP_1_1).build();
    }

    public InferenceResponse infer(Path wav) {
        try {
            var req = HttpRequest.newBuilder(URI.create(props.inferenceServiceUrl() + "/infer")).timeout(Duration.ofMillis(props.inferenceTimeoutMs())).header("Content-Type", "audio/wav").POST(HttpRequest.BodyPublishers.ofFile(wav)).build();
            var res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() < 200 || res.statusCode() >= 300)
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "INFERENCE_UNAVAILABLE", "Inference service is unavailable");
            if (res.body() == null || res.body().isBlank())
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "INFERENCE_INVALID_RESPONSE", "Inference service returned an invalid response");
            return parseResponse(res.body());
        } catch (ApiException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "INFERENCE_TIMEOUT", "Inference service timed out");
        } catch (Exception e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "INFERENCE_UNAVAILABLE", "Inference service is unavailable");
        }
    }

    InferenceResponse parseResponse(String body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            if (node == null || !node.isObject() || !node.hasNonNull("age") || !node.hasNonNull("female") ||
                    !node.hasNonNull("male") || !node.hasNonNull("child") || !node.get("age").isNumber() ||
                    !node.get("female").isNumber() || !node.get("male").isNumber() || !node.get("child").isNumber()) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "INFERENCE_INVALID_RESPONSE", "Inference service returned an invalid response");
            }
            InferenceResponse response = objectMapper.treeToValue(node, InferenceResponse.class);
            validate(response);
            return response;
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "INFERENCE_INVALID_RESPONSE", "Inference service returned an invalid response");
        }
    }

    private void validate(InferenceResponse response) {
        if (!Double.isFinite(response.age()) || response.age() < 0 || response.age() > 100 ||
                !probability(response.female()) || !probability(response.male()) || !probability(response.child()) ||
                Math.abs(response.female() + response.male() + response.child() - 1.0) > 0.01) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "INFERENCE_INVALID_RESPONSE", "Inference service returned an invalid response");
        }
    }

    private boolean probability(double value) {
        return Double.isFinite(value) && value >= 0 && value <= 1;
    }

    public boolean healthy() {
        try {
            var r = http.send(HttpRequest.newBuilder(URI.create(props.inferenceServiceUrl() + "/health")).timeout(Duration.ofMillis(800)).GET().build(), HttpResponse.BodyHandlers.discarding());
            return r.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
