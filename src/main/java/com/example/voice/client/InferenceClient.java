package com.example.voice.client;

import com.example.voice.dto.InferenceResponse;

import java.nio.file.Path;

public interface InferenceClient {
    InferenceResponse infer(Path normalizedWav);

    boolean healthy();
}
