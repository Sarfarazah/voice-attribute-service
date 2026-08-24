package com.example.voice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.example.voice.config.VoiceProperties;

@SpringBootApplication
@EnableConfigurationProperties(VoiceProperties.class)
public class VoiceAttributeServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(VoiceAttributeServiceApplication.class, args);
    }

}
