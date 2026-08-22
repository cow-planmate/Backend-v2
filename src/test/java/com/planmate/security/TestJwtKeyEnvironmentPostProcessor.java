package com.planmate.security;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Map;

/**
 * 테스트 컨텍스트에 RS256 서명 키를 넣어 준다. 테스트 클래스패스에만 있다
 * ({@code src/test/resources/META-INF/spring.factories}).
 *
 * <p>{@link JwtSigningKeys} 가 키 없이는 기동하지 않게 되면서 필요해졌다. 예전에는 키가 없으면
 * HS256 으로 조용히 떨어졌기 때문에 모든 {@code @SpringBootTest} 가 <b>대칭키 모드로</b>
 * 돌고 있었다 — 운영은 RS256 인데 테스트는 한 번도 그 경로를 밟지 않았다는 뜻이다.
 *
 * <p>키를 저장소에 커밋하지 않고 JVM 마다 새로 만든다. 개인키를 파일로 두면 시크릿 스캐너가
 * 걸고, "테스트용"이라는 주석은 시간이 지나면 아무도 안 읽는다. 생성 비용은 JVM 당 한 번뿐이다.
 */
public class TestJwtKeyEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String PROPERTY = "jwt.private-key";

    /** JVM 당 한 번만 만든다. 컨텍스트가 여러 개 떠도 같은 키를 쓴다. */
    private static final String PEM = generatePkcs8Pem();

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // 개별 테스트가 @TestPropertySource 로 다른 키를 주입했다면 그쪽을 존중한다.
        if (environment.containsProperty(PROPERTY)
                && !environment.getProperty(PROPERTY, "").isBlank()) {
            return;
        }
        // addFirst 여야 한다. application.yml 이 jwt.private-key 를 ${JWT_PRIVATE_KEY:} 로
        // 선언하고 있어서, 환경변수가 없는 테스트에서는 "빈 문자열"이라는 값이 이미 존재한다.
        // addLast 로 넣으면 그 빈 값에 밀려 아무 효과가 없다.
        environment.getPropertySources()
                .addFirst(new MapPropertySource("test-jwt-signing-key", Map.of(PROPERTY, PEM)));
    }

    private static String generatePkcs8Pem() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            return "-----BEGIN PRIVATE KEY-----\n"
                    + Base64.getMimeEncoder().encodeToString(pair.getPrivate().getEncoded())
                    + "\n-----END PRIVATE KEY-----\n";
        } catch (Exception e) {
            throw new IllegalStateException("테스트용 RSA 키를 만들지 못했다", e);
        }
    }
}
