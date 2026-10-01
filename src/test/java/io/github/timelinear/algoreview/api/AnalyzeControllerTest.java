package io.github.timelinear.algoreview.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import io.github.timelinear.algoreview.analysis.Analysis;
import io.github.timelinear.algoreview.analysis.AnalysisException;
import io.github.timelinear.algoreview.analysis.AnalysisService;
import io.github.timelinear.algoreview.config.ClientProperties;

/**
 * HTTP 계약만 본다: 요청 필드명, platform enum, 인증 헤더, 에러 응답 형식. 서비스는 가짜.
 */
@WebMvcTest(AnalyzeController.class)
@EnableConfigurationProperties(ClientProperties.class) // 필터가 쓰는 설정 바인딩. WebMvcTest 슬라이스에는 포함되지 않는다
@TestPropertySource(properties = "client.api-key=test-client-key")
class AnalyzeControllerTest {

    static final String KEY = "test-client-key";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AnalysisService service;

    static final Analysis ANALYSIS = new Analysis("접근", "O(N)", "O(1)", "개선", "다음 문제", "code",
            List.of("implementation"));

    // 실제로 발생하는 입력: 확장 feat/jungol의 buildJungolWebhookPayload 출력 형태(사용자 확인, docs/decisions.md D-29).
    // language는 소문자 정규화 값, level은 "Silver III" 또는 null, memory는 KB, time은 ms.
    static final String JUNGOL_BODY = """
            {"platform":"jungol",
             "meta_info":{"title":"두 수의 합","problem_id":"1000","link":"https://jungol.co.kr/problem/1000",
                          "level":"Silver III","language":"java"},
             "submission_info":{"code":"class Main {}","memory":14000,"time":120}}
            """;

    private static MockHttpServletRequestBuilder analyze(String body) {
        return post("/analyze").header("X-Api-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void jungolIsAcceptedAndResponseKeepsSnakeCaseFields() throws Exception {
        given(service.analyze(any())).willReturn(ANALYSIS);

        mvc.perform(analyze(JUNGOL_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.analysis.time_complexity").value("O(N)"))
                .andExpect(jsonPath("$.analysis.better_code").value("code"))
                .andExpect(jsonPath("$.analysis.next_problem").value("다음 문제"))
                .andExpect(jsonPath("$.analysis.tags[0]").value("implementation"));

        ArgumentCaptor<AnalyzeRequest> captor = ArgumentCaptor.forClass(AnalyzeRequest.class);
        verify(service).analyze(captor.capture());
        assertThat(captor.getValue().platform()).isEqualTo(Platform.JUNGOL);
        assertThat(captor.getValue().metaInfo().problemId()).isEqualTo("1000");
        assertThat(captor.getValue().metaInfo().level()).isEqualTo("Silver III");
        assertThat(captor.getValue().metaInfo().language()).isEqualTo("java");
    }

    // 실제로 발생하는 입력: level이 null인 정올 문제(난이도 미표기).
    @Test
    void nullLevelIsAccepted() throws Exception {
        given(service.analyze(any())).willReturn(ANALYSIS);
        mvc.perform(analyze(JUNGOL_BODY.replace("\"Silver III\"", "null")))
                .andExpect(status().isOk());
    }

    // 합성 입력: 키 헤더 없음. 확장에 키를 설정하지 않았거나 다른 클라이언트가 호출한 경우.
    @Test
    void missingApiKeyIs401() throws Exception {
        mvc.perform(post("/analyze").contentType(MediaType.APPLICATION_JSON).content(JUNGOL_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    // 합성 입력: 키가 다름(한 글자 차이).
    @Test
    void wrongApiKeyIs401() throws Exception {
        mvc.perform(post("/analyze").header("X-Api-Key", KEY + "x").contentType(MediaType.APPLICATION_JSON)
                        .content(JUNGOL_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    // 합성 입력: 원작자 enum에 없는 값. 확장이 보낼 일은 없지만 다른 클라이언트가 보낼 수 있다.
    @Test
    void unknownPlatformIs400() throws Exception {
        mvc.perform(analyze(JUNGOL_BODY.replace("\"jungol\"", "\"codeforces\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    // 합성 입력: submission_info 자체가 빠진 요청. 필수 누락 400과 메시지에 JSON 필드명이 들어가는지 본다.
    @Test
    void missingRequiredFieldIs400WithJsonFieldName() throws Exception {
        String body = """
                {"platform":"baekjoon","meta_info":{"title":"t","problem_id":"1","language":"java"}}
                """;
        mvc.perform(analyze(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.message", containsString("submission_info")));
    }

    // 합성 입력: 중첩 필드 누락(problem_id).
    @Test
    void missingNestedFieldIs400() throws Exception {
        mvc.perform(analyze(JUNGOL_BODY.replace("\"problem_id\":\"1000\",", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message", containsString("meta_info.problem_id")));
    }

    // 합성 입력: 깨진 JSON.
    @Test
    void malformedJsonIs400() throws Exception {
        mvc.perform(analyze("{\"platform\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    // 합성 입력: 서비스가 UPSTREAM_TIMEOUT을 던지면 502와 에러 형식으로 바뀌는지.
    @Test
    void analysisExceptionMapsToErrorFormat() throws Exception {
        given(service.analyze(any())).willThrow(new AnalysisException.UpstreamTimeout("timed out", null));

        mvc.perform(analyze(JUNGOL_BODY))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error.code").value("UPSTREAM_TIMEOUT"))
                .andExpect(jsonPath("$.error.message").value("timed out"));
    }

    // 합성 입력: 한도 초과는 429 DAILY_LIMIT.
    @Test
    void dailyLimitIs429() throws Exception {
        given(service.analyze(any())).willThrow(new AnalysisException.DailyLimit("limit"));

        mvc.perform(analyze(JUNGOL_BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("DAILY_LIMIT"));
    }

    // 합성 입력: DB 접속 실패 같은 예상 밖 예외. 500 INTERNAL_ERROR, 메시지는 고정 문구(내부 정보 비노출, D-28).
    @Test
    void unexpectedExceptionIs500WithFixedMessage() throws Exception {
        given(service.analyze(any())).willThrow(new IllegalStateException("jdbc:postgresql://secret-host/db down"));

        mvc.perform(analyze(JUNGOL_BODY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.error.message").value("internal server error"));
    }
}
