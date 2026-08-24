package com.example.voice.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum AgeBracket {
    AGE_18_30("18-30"), AGE_31_45("31-45"), AGE_46_60("46-60"), AGE_60_PLUS("60+"), unknown("unknown");
    private final String value;

    AgeBracket(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
