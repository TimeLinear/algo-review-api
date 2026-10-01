package io.github.timelinear.algoreview.analysis;

/**
 * LLM 호출 경계. 실제 구현은 Anthropic API를 호출하고, 테스트에서는 가짜 구현으로 바꾼다.
 * HTTP 수준의 재시도와 타임아웃 분류는 구현체 책임이고, 출력 내용 검증과 재요청은 {@link AnalysisService} 책임이다.
 */
public interface ReviewGenerator {

    /**
     * @return 원문 텍스트와 사용량. 통신 실패는 {@link AnalysisException.UpstreamTimeout} 또는
     *         {@link AnalysisException.UpstreamError}로 던진다.
     */
    ReviewResult generate(ReviewInput input);
}
