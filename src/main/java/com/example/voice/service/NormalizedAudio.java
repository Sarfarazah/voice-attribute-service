package com.example.voice.service;

import com.example.voice.model.AudioQuality;

import java.nio.file.Path;

public record NormalizedAudio(Path path, double durationSeconds, AudioQuality quality) {
}
