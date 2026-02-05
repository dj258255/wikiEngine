package com.wiki.engine.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;

/**
 * JWT 토큰 생성 및 검증을 담당하는 컴포넌트.
 * jjwt 라이브러리를 사용하며, HMAC-SHA 알고리즘으로 서명한다.
 * 토큰의 subject에 userId, claim에 username을 저장한다.
 */
@Component
public class JwtTokenProvider {

    /** HMAC-SHA 서명용 비밀키 */
    private final SecretKey key;

    /** 토큰 만료 시간 (밀리초, 기본 30분 = 1800000ms) */
    private final long expiration;

    /**
     * application.yml에서 jwt.secret(Base64 인코딩)과 jwt.expiration 값을 주입받아 초기화한다.
     *
     * @param secret Base64로 인코딩된 비밀키 문자열
     * @param expiration 토큰 만료 시간 (밀리초)
     */
    public JwtTokenProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration}") long expiration) {
        this.key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(secret));
        this.expiration = expiration;
    }

    /**
     * JWT 토큰을 생성한다.
     *
     * @param userId 사용자 ID (subject에 저장)
     * @param username 사용자명 (claim에 저장)
     * @return 서명된 JWT 토큰 문자열
     */
    public String createToken(Long userId, String username) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(key)
                .compact();
    }

    /**
     * 토큰에서 userId를 추출한다.
     *
     * @param token JWT 토큰 문자열
     * @return 사용자 ID
     */
    public Long getUserId(String token) {
        Claims claims = parseClaims(token);
        return Long.parseLong(claims.getSubject());
    }

    /**
     * 토큰에서 username을 추출한다.
     *
     * @param token JWT 토큰 문자열
     * @return 사용자명
     */
    public String getUsername(String token) {
        Claims claims = parseClaims(token);
        return claims.get("username", String.class);
    }

    /**
     * 토큰의 유효성을 검증한다.
     * 서명 검증과 만료 시간 확인을 수행한다.
     *
     * @param token JWT 토큰 문자열
     * @return 유효하면 true, 아니면 false
     */
    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 토큰의 남은 만료 시간을 밀리초 단위로 반환한다.
     * 블랙리스트에서 토큰 만료까지의 TTL을 계산할 때 사용할 수 있다.
     *
     * @param token JWT 토큰 문자열
     * @return 남은 만료 시간 (밀리초)
     */
    public long getExpirationTime(String token) {
        Claims claims = parseClaims(token);
        return claims.getExpiration().getTime() - System.currentTimeMillis();
    }

    /**
     * JWT 토큰을 파싱하여 Claims 객체를 반환한다.
     * 서명 검증 및 만료 시간 확인이 자동으로 수행된다.
     *
     * @param token JWT 토큰 문자열
     * @return 파싱된 Claims 객체
     */
    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
