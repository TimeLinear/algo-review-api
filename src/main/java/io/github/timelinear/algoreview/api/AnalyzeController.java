package io.github.timelinear.algoreview.api;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.github.timelinear.algoreview.analysis.Analysis;
import io.github.timelinear.algoreview.analysis.AnalysisService;
import jakarta.validation.Valid;

/**
 * POST /analyze. 본문 검증(필수 필드, platform enum)은 Bean Validation과 Jackson이,
 * 그 외 규칙과 흐름은 {@link AnalysisService}가 맡는다.
 */
@RestController
public class AnalyzeController {

    private final AnalysisService service;

    public AnalyzeController(AnalysisService service) {
        this.service = service;
    }

    @PostMapping("/analyze")
    public AnalyzeResponse analyze(@Valid @RequestBody AnalyzeRequest request) {
        Analysis analysis = service.analyze(request);
        return AnalyzeResponse.of(analysis);
    }
}
