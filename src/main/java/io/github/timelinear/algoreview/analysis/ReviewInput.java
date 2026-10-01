package io.github.timelinear.algoreview.analysis;

import io.github.timelinear.algoreview.api.AnalyzeRequest;
import io.github.timelinear.algoreview.api.Platform;

/**
 * LLM에 넘길 입력. HTTP 요청 객체와 분리해 두어 ReviewGenerator가 api 계층에 의존하지 않게 한다.
 */
public record ReviewInput(
        Platform platform,
        String title,
        String problemId,
        String link,
        String level,
        String language,
        String code,
        Integer memory,
        Integer time) {

    public static ReviewInput from(AnalyzeRequest req) {
        AnalyzeRequest.MetaInfo m = req.metaInfo();
        AnalyzeRequest.SubmissionInfo s = req.submissionInfo();
        return new ReviewInput(req.platform(), m.title(), m.problemId(), m.link(), m.level(), m.language(),
                s.code(), s.memory(), s.time());
    }
}
