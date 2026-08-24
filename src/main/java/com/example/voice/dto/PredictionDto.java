package com.example.voice.dto;

public record PredictionDto<T>(T prediction, double confidence) {
}

