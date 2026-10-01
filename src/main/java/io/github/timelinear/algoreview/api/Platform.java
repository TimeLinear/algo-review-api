package io.github.timelinear.algoreview.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 요청의 platform 값. 원작자 서버 enum 4개(baekjoon, swea, programmers, leetcode) + jungol.
 * JSON 값은 소문자 그대로이며, 그 외 값은 역직렬화 단계에서 거부되어 400이 된다.
 */
public enum Platform {
    BAEKJOON("baekjoon"),
    SWEA("swea"),
    PROGRAMMERS("programmers"),
    LEETCODE("leetcode"),
    JUNGOL("jungol");

    private final String value;

    Platform(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static Platform fromJson(String raw) {
        for (Platform p : values()) {
            if (p.value.equals(raw)) {
                return p;
            }
        }
        throw new IllegalArgumentException("platform must be one of baekjoon, swea, programmers, leetcode, jungol");
    }
}
