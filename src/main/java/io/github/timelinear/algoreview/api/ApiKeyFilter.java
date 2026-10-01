package io.github.timelinear.algoreview.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import io.github.timelinear.algoreview.analysis.ErrorCode;
import io.github.timelinear.algoreview.config.ClientProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * 모든 요청의 X-Api-Key 헤더를 환경변수 CLIENT_API_KEY와 비교한다. /actuator/health만 예외.
 * 비교는 길이에 무관한 상수 시간(MessageDigest.isEqual)으로 하여 타이밍으로 키를 추정하지 못하게 한다.
 * 서버에 키가 설정되지 않았으면 모든 요청을 거부한다(열린 채로 뜨지 않도록).
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Api-Key";
    static final String HEALTH_PATH = "/actuator/health";

    private final byte[] expected;
    private final ObjectMapper mapper;

    public ApiKeyFilter(ClientProperties props, ObjectMapper mapper) {
        String key = props.apiKey() == null ? "" : props.apiKey();
        this.expected = key.getBytes(StandardCharsets.UTF_8);
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return HEALTH_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        if (expected.length == 0 || provided == null
                || !MessageDigest.isEqual(expected, provided.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(ErrorCode.UNAUTHORIZED.status().value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(mapper.writeValueAsString(
                    ErrorResponse.of(ErrorCode.UNAUTHORIZED, "missing or invalid " + HEADER)));
            return;
        }
        chain.doFilter(request, response);
    }
}
