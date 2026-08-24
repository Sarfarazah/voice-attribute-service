package com.example.voice.service;

import com.example.voice.config.VoiceProperties;
import com.example.voice.exception.ApiException;
import com.example.voice.model.AudioQuality;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

@Service
public class AudioNormalizer {
    private final VoiceProperties props;

    public AudioNormalizer(VoiceProperties props) {
        this.props = props;
    }

    public NormalizedAudio normalize(InputStream stream, long contentLength) {
        if (contentLength == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_AUDIO", "Audio file is empty");
        if (contentLength > props.maxFileBytes())
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "Audio upload exceeds the configured size limit");
        Path input = null, output = null;
        Process process = null;
        try {
            input = Files.createTempFile("voice-input-", ".audio");
            output = Files.createTempFile("voice-normalized-", ".wav");
            try (OutputStream out = Files.newOutputStream(input)) {
                byte[] b = new byte[8192];
                int n;
                long total = 0;
                while ((n = stream.read(b)) != -1) {
                    total += n;
                    if (total > props.maxFileBytes())
                        throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "Audio upload exceeds the configured size limit");
                    out.write(b, 0, n);
                }
                if (total == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_AUDIO", "Audio file is empty");
            }
            process = new ProcessBuilder("ffmpeg", "-v", "error", "-y", "-i", input.toString(), "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", output.toString())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NORMALIZATION_TIMEOUT", "Audio normalization timed out");
            }
            if (process.exitValue() != 0)
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AUDIO", "Audio cannot be decoded or is unsupported");
            WavStats s = readWavStats(output);
            if (s.duration > props.maxAudioDurationSeconds())
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AUDIO_TOO_LONG", "Audio duration exceeds the configured limit");
            AudioQuality q = quality(s);
            return new NormalizedAudio(output, s.duration, q);
        } catch (ApiException e) {
            delete(output);
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            delete(output);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NORMALIZATION_TIMEOUT", "Audio normalization timed out");
        } catch (IOException e) {
            delete(output);
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AUDIO", "Audio cannot be decoded or is unsupported");
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            delete(input);
        }
    }

    public void delete(Path path) {
        if (path != null) try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private AudioQuality quality(WavStats s) {
        return AudioQualityAssessor.assess(s.duration, s.rms, s.nonSilentRatio, s.clipRatio, props);
    }

    private WavStats readWavStats(Path f) throws IOException {
        byte[] data = Files.readAllBytes(f);
        if (data.length < 44 || data[0] != 'R' || data[1] != 'I' || data[2] != 'F' || data[3] != 'F')
            throw new IOException();
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int pos = 12, rate = 0, bits = 0, channels = 0, dataPos = 0, dataLen = 0;
        while (pos + 8 <= data.length) {
            String id = new String(data, pos, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int len = b.getInt(pos + 4);
            if (pos + 8 + len > data.length) break;
            if ("fmt ".equals(id)) {
                channels = b.getShort(pos + 10);
                rate = b.getInt(pos + 12);
                bits = b.getShort(pos + 22);
            }
            if ("data".equals(id)) {
                dataPos = pos + 8;
                dataLen = len;
                break;
            }
            pos += 8 + len + (len & 1);
        }
        if (rate <= 0 || channels != 1 || bits != 16 || dataLen < 2) throw new IOException();
        int samples = dataLen / 2;
        double sum = 0;
        int non = 0, clip = 0;
        for (int i = 0; i < samples; i++) {
            short v = b.getShort(dataPos + i * 2);
            double x = v / 32768.0;
            sum += x * x;
            if (Math.abs(x) > 0.015) non++;
            if (Math.abs(x) > 0.99) clip++;
        }
        return new WavStats(samples / (double) rate, Math.sqrt(sum / samples), non / (double) samples, clip / (double) samples);
    }

    private record WavStats(double duration, double rms, double nonSilentRatio, double clipRatio) {
    }
}
