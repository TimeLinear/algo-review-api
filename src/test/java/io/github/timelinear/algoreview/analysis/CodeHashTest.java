package io.github.timelinear.algoreview.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.timelinear.algoreview.api.Platform;

class CodeHashTest {

    // 실제로 발생하는 입력: Windows 편집기에서 복사한 코드는 CRLF, 확장이 긁은 코드는 LF로 올 수 있다.
    @Test
    void crlfAndLfProduceSameHash() {
        String lf = "public class Main {\n    public static void main(String[] a) {}\n}\n";
        String crlf = lf.replace("\n", "\r\n");
        assertThat(CodeHash.of(Platform.BAEKJOON, "1000", "Java", crlf))
                .isEqualTo(CodeHash.of(Platform.BAEKJOON, "1000", "Java", lf));
    }

    // 실제로 발생하는 입력: 제출 페이지에서 긁은 코드는 끝에 빈 줄이 붙기도 한다.
    @Test
    void trailingWhitespaceIgnoredButLeadingKept() {
        String base = "print(1)";
        assertThat(CodeHash.of(Platform.LEETCODE, "1", "Python", base + "\n\n  "))
                .isEqualTo(CodeHash.of(Platform.LEETCODE, "1", "Python", base));
        assertThat(CodeHash.of(Platform.LEETCODE, "1", "Python", "  " + base))
                .isNotEqualTo(CodeHash.of(Platform.LEETCODE, "1", "Python", base));
    }

    // 실제로 발생하는 입력: 백준 1000번과 정올 1000번은 다른 문제다. 같은 코드라도 키가 달라야 한다.
    @Test
    void platformProblemAndLanguageAreInKey() {
        String code = "int main(){}";
        String a = CodeHash.of(Platform.BAEKJOON, "1000", "C++", code);
        assertThat(CodeHash.of(Platform.JUNGOL, "1000", "C++", code)).isNotEqualTo(a);
        assertThat(CodeHash.of(Platform.BAEKJOON, "1001", "C++", code)).isNotEqualTo(a);
        assertThat(CodeHash.of(Platform.BAEKJOON, "1000", "C", code)).isNotEqualTo(a);
    }

    @Test
    void hashIsLowercaseHexSha256() {
        assertThat(CodeHash.of(Platform.SWEA, "1", "Java", "x")).matches("[0-9a-f]{64}");
    }
}
