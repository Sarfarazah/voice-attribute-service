package com.example.voice.controller;

import com.example.voice.dto.AnalyzeResponse;
import com.example.voice.exception.ApiException;
import com.example.voice.service.AnalysisService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

@RestController
public class AnalyzeController {
    private final AnalysisService service;

    public AnalyzeController(AnalysisService s) {
        service = s;
    }

    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AnalyzeResponse multipart(@RequestParam("contact_id") UUID contactId, @RequestParam("audio") MultipartFile audio) throws IOException {
        return service.analyze(contactId, audio.getInputStream(), audio.getSize());
    }

    @PostMapping(value = "/analyze", consumes = {"audio/wav", "audio/x-wav", "audio/mpeg", "audio/mp3", "audio/ogg", "audio/flac", "audio/aac"})
    public AnalyzeResponse raw(@RequestParam("contact_id") UUID contactId, HttpServletRequest request) throws IOException {
        long length = request.getContentLengthLong();
        return service.analyze(contactId, request.getInputStream(), length);
    }
}
