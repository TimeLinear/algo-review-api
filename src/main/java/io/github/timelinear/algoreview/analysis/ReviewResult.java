package io.github.timelinear.algoreview.analysis;

/**
 * LLM 호출 1회의 결과. 내용 검증 전의 원문이다.
 *
 * @param text         모델이 돌려준 텍스트(구조화 출력이면 JSON 문자열). 텍스트 블록이 없으면 null
 * @param stopReason   Anthropic 응답의 stop_reason. "end_turn"만 정상
 * @param model        실제 응답한 모델 ID
 * @param inputTokens  usage.input_tokens
 * @param outputTokens usage.output_tokens
 */
public record ReviewResult(
        String text,
        String stopReason,
        String model,
        int inputTokens,
        int outputTokens) {

    public boolean isComplete() {
        return "end_turn".equals(stopReason);
    }
}
