package io.github.timelinear.algoreview.api;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.timelinear.algoreview.analysis.Analysis;
import io.github.timelinear.algoreview.analysis.AnalysisException;
import io.github.timelinear.algoreview.analysis.AnalysisService;

/**
 * HTTP 계약만 본다: 요청 필드명, platform enum, 에러 응답 형식. 서비스는 가짜.
 */
@WebMvcTest(AnalyzeController.class)
class AnalyzeControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AnalysisService service;

    static final Analysis ANALYSIS = new Analysis("접근", "O(N)", "O(1)", "개선", "다음 문제", "code",
            List.of("implementation"));

    // 실제로 발생하는 입력: 확장(payload_builder.js)이 보내는 형태 그대로. 정올 제출.
    static final String JUNGOL_BODY = """
            {"platform":"jungol",
             "meta_info":{"title":"두 수의 합","problem_id":"1000","link":"https://jungol.co.kr/problem/1000",
                          "level":null,"language":"Java"},
             "submission_info":{"code":"class Main {}","memory":14000,"time":120}}
            """;

    @Test
    void jungolIsAcceptedAndResponseKeepsSnakeCaseFields() throws Exception {
        given(service.analyze(any())).willReturn(ANALYSIS);

        mvc.perform(post("/analyze").contentType(MediaType.APPLICATION_JSON).content(JUNGOL_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.analysis.time_complexity").value("O(N)"))
                .andExpect(jsonPath("$.analysis.better_code").value("code"))
                .andExpect(jsonPath("$.analysis.next_problem").value("다음 문제"))
                .andExpect(jsonPath("$.analysis.tags[0]").value("implementation"));

        ArgumentCaptor<AnalyzeRequest> captor = ArgumentCaptor.forClass(AnalyzeRequest.class);
        org.mockito.Mockito.verify(service).analyze(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().platform()).isEqualTo(Platform.JUNGOL);
        org.assertj.core.api.Assertions.assertThat(captor.getValue().metaInfo().problemId()).isEqualTo("1000");
    }

    // 합성 입력: 원작자 enum에 없는 값. 확장이 보낼 일은 없지만 다른 클라이언트가 보낼 수 있다.
    @Test
    void unknownPlatformIs400() throws Exception {
        mvc.perform(post("/analyze").contentType(MediaType.APPLICATION_JSON)
                        .content(JUNGOL_BODY.replace("\"jungol\"", "\"codeforces\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    // 합성 입력: submission_info 자체가 빠진 요청. 필수 누락 400과 메시지에 JSON 필드명이 들어가는지 본다.
    @Test
    void missingRequiredFieldIs400WithJsonFieldName() throws Exception {
        String body = """
                {"platform":"baekjoon","meta_info":{"title":"t","problem_id":"1","language":"Java"}}
                """;
        mvc.perform(post("/analyze").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.message", containsString("submission_info")));
    }

    // 합성 입력: 중첩 필드 누락(problem_id).
    @Test
    void missingNestedFieldIs400() throws Exception {
        String body = JUNGOL_BODY.replace("\"problem_id\":\"1000\",", "");
        mvc.perform(post("/analyze").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message", containsString("meta_info.problem_id")));
    }

    // 합성 입력: 깨진 JSON.
    @Test
    void malformedJsonIs400() throws Exception {
        mvc.perform(post("/analyze").contentType(MediaType.APPLICATION_JSON).content("{\"platform\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    // 합성 입력: 서비스가 UPSTREAM_TIMEOUT을 던지면 502와 에러 형식으로 바뀌는지.
    @Test
    void analysisExceptionMapsToErrorFormat() throws Exception {
        given(service.analyze(any())).willThrow(new AnalysisException.UpstreamTimeout("timed out", null));

        mvc.perform(post("/analyze").contentType(MediaType.APPLICATION_JSON).content(JUNGOL_BODY))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error.code").value("UPSTREAM_TIMEOUT"))
                .andExpect(jsonPath("$.error.message").value("timed out"));
    }
}
