package com.example.voice.service;

import com.example.voice.client.InferenceClient;
import com.example.voice.config.VoiceProperties;
import com.example.voice.dto.*;
import com.example.voice.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.UUID;

@Service
public class AnalysisService {
    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);
    private final AudioNormalizer normalizer;
    private final InferenceClient inference;
    private final VoiceProperties props;

    public AnalysisService(AudioNormalizer n, InferenceClient i, VoiceProperties p) {
        normalizer = n;
        inference = i;
        props = p;
    }

    public AnalyzeResponse analyze(UUID contact, InputStream audio, long len) {
        long start = System.nanoTime();
        NormalizedAudio n = normalizer.normalize(audio, len);
        try {
            PredictionDto<GenderPrediction> g;
            PredictionDto<AgeBracket> a;
            if (n.quality() == AudioQuality.insufficient) {
                g = new PredictionDto<>(GenderPrediction.unknown, 0);
                a = new PredictionDto<>(AgeBracket.unknown, 0);
            } else {
                InferenceResponse r = inference.infer(n.path());
                g = gender(r);
                a = age(r, n.quality());
            }
            long ms = (System.nanoTime() - start) / 1_000_000;
            log.info("contactId={} durationMs={} quality={} processingMs={} result=success", contact, Math.round(n.durationSeconds() * 1000), n.quality(), ms);
            return new AnalyzeResponse(contact, g, a, ms, n.quality());
        } finally {
            normalizer.delete(n.path());
        }
    }

    private PredictionDto<GenderPrediction> gender(InferenceResponse r) {
        double c = clamp(Math.max(r.female(), r.male()));
        if (c < props.genderConfidenceThreshold() || r.child() >= c)
            return new PredictionDto<>(GenderPrediction.unknown, c);
        return new PredictionDto<>(r.male() >= r.female() ? GenderPrediction.male : GenderPrediction.female, c);
    }

    private PredictionDto<AgeBracket> age(InferenceResponse r, AudioQuality q) {
        double years = r.age();
        if (!Double.isFinite(years) || years < 18 || years > 100 || r.child() >= Math.max(r.female(), r.male()))
            return new PredictionDto<>(AgeBracket.unknown, 0);
        AgeBracket bracket = years <= 30 ? AgeBracket.AGE_18_30 : years <= 45 ? AgeBracket.AGE_31_45 : years <= 60 ? AgeBracket.AGE_46_60 : AgeBracket.AGE_60_PLUS;
        double bracketHalfWidth = bracket == AgeBracket.AGE_18_30 ? 6 : bracket == AgeBracket.AGE_60_PLUS ? 20 : 7.5;
        double nearestBoundary = switch (bracket) {
            case AGE_18_30 -> Math.min(years - 18, 30 - years);
            case AGE_31_45 -> Math.min(years - 30, 45 - years);
            case AGE_46_60 -> Math.min(years - 45, 60 - years);
            case AGE_60_PLUS -> Math.min(years - 60, 100 - years);
            case unknown -> 0;
        };
        // This is a conservative bracket-stability heuristic, not calibrated probability.
        double c = clamp(0.35 + 0.45 * clamp(nearestBoundary / bracketHalfWidth));
        if (q == AudioQuality.degraded) c *= 0.75;
        if (c < props.ageConfidenceThreshold()) return new PredictionDto<>(AgeBracket.unknown, c);
        return new PredictionDto<>(bracket, c);
    }

    private double clamp(double n) {
        return Math.max(0, Math.min(1, n));
    }
}
