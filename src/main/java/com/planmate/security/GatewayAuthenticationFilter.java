package com.planmate.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Gateway가 검증해 재주입한 내부 신원 헤더로 SecurityContext를 구성한다. */
@Component
public class GatewayAuthenticationFilter extends OncePerRequestFilter {

    static final String USER_ID_HEADER = "X-User-Id";
    static final String USER_ROLE_HEADER = "X-User-Role";
    private static final Set<String> ALLOWED_ROLES = Set.of("USER", "ADMIN");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String userId = singleHeader(request, USER_ID_HEADER);
        String role = singleHeader(request, USER_ROLE_HEADER);
        if (request.getHeader(USER_ID_HEADER) != null || request.getHeader(USER_ROLE_HEADER) != null) {
            SecurityContextHolder.clearContext();
        }
        if (userId != null && role != null && ALLOWED_ROLES.contains(role)) {
            try {
                UUID principal = UUID.fromString(userId);
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, null,
                                List.of(new SimpleGrantedAuthority("ROLE_" + role))));
            } catch (IllegalArgumentException ignored) {
                // 잘못된 내부 헤더는 인증으로 승격하지 않는다. 보호 경로는 Security가 401로 막는다.
            }
        }

        chain.doFilter(request, response);
    }

    private static String singleHeader(HttpServletRequest request, String name) {
        List<String> values = Collections.list(request.getHeaders(name));
        return values.size() == 1 && !values.get(0).isBlank() ? values.get(0) : null;
    }
}
