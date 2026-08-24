package com.example.voice.dto;

import com.example.voice.model.*;

import java.util.UUID;

public record AnalyzeResponse(UUID contact_id, PredictionDto<GenderPrediction> gender,
                              PredictionDto<AgeBracket> age_bracket, long processing_ms,
                              AudioQuality audio_quality) {
}
