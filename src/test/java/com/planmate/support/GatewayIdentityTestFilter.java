package com.planmate.support;

import com.planmate.security.JwtTokenProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 기존 MockMvc 테스트의 Bearer 요청을 실제 Gateway 출력 계약으로 변환한다.
 * 운영 코드에는 포함되지 않으며, 새 인증 경계 자체는 GatewayAuthenticationFilterTest에서
 * X-User-Id/X-User-Role을 직접 보내 검증한다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GatewayIdentityTestFilter extends OncePerRequestFilter {
    private final JwtTokenProvider tokens;

    public GatewayIdentityTestFilter(JwtTokenProvider tokens) {
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }

        String token = authorization.substring(7);
        if (!tokens.validateToken(token) || !tokens.isAccessToken(token)) {
            chain.doFilter(request, response);
            return;
        }

        String userId = tokens.extractUserId(token).toString();
        String role = tokens.extractRole(token);
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override
            public String getHeader(String name) {
                if ("X-User-Id".equalsIgnoreCase(name)) return userId;
                if ("X-User-Role".equalsIgnoreCase(name)) return role;
                return super.getHeader(name);
            }

            @Override
            public Enumeration<String> getHeaders(String name) {
                if ("X-User-Id".equalsIgnoreCase(name)) return Collections.enumeration(Set.of(userId));
                if ("X-User-Role".equalsIgnoreCase(name)) return Collections.enumeration(Set.of(role));
                return super.getHeaders(name);
            }

            @Override
            public Enumeration<String> getHeaderNames() {
                Set<String> names = new LinkedHashSet<>(Collections.list(super.getHeaderNames()));
                names.add("X-User-Id");
                names.add("X-User-Role");
                return Collections.enumeration(names);
            }
        }, response);
    }
}
