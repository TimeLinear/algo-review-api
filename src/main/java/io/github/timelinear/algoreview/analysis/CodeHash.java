package io.github.timelinear.algoreview.analysis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import io.github.timelinear.algoreview.api.Platform;

/**
 * 캐시 키이자 로그 식별자. SHA-256(platform + "\n" + problem_id + "\n" + language + "\n" + code).
 * code는 CRLF를 LF로 바꾸고 문자열 끝 공백만 제거한다(편집기 차이로 같은 코드가 다른 키가 되지 않도록).
 * prompt_version은 키에 넣지 않는다(docs/decisions.md D-21).
 */
public final class CodeHash {

    private CodeHash() {
    }

    public static String of(Platform platform, String problemId, String language, String code) {
        String normalized = normalize(code);
        String material = platform.value() + "\n" + problemId + "\n" + language + "\n" + normalized;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    static String normalize(String code) {
        return code.replace("\r\n", "\n").stripTrailing();
    }
}
