package com.planmate.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 요청마다 로그 상관관계 키를 MDC 에 싣는다.
 *
 * traceId 는 게이트웨이가 만들어 {@code X-Request-Id} 로 넘겨준다(Gateway RequestIdFilter).
 * 그 값을 그대로 쓰는 것이 핵심이다 — 여기서 매번 새로 만들면 서비스마다 다른 ID 가 찍혀
 * 서비스 경계를 넘는 순간 추적이 끊긴다. 게이트웨이를 거치지 않는 경로(로컬 개발, 내부
 * 호출)를 위해 없을 때만 생성한다.
 */
@Component
public class MdcLoggingFilter extends OncePerRequestFilter {

    private static final String TRACE_ID_KEY = "traceId";
    private static final String USER_ID_KEY = "userId";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    /** 로그 위조를 막기 위한 형식 제한. 게이트웨이의 RequestIdFilter 와 같은 규칙이다. */
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            MDC.put(TRACE_ID_KEY, traceId(request.getHeader(REQUEST_ID_HEADER)));

            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() != null) {
                MDC.put(USER_ID_KEY, String.valueOf(authentication.getPrincipal()));
            }

            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }

    private static String traceId(String incoming) {
        if (incoming != null && SAFE_TRACE_ID.matcher(incoming).matches()) {
            return incoming;
        }
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
