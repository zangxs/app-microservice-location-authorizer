package com.brayanpv.authorizer;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;

/**
 * Validates JWT tokens using HS256 with a shared secret from environment variable JWT_SECRET.
 * No Spring — uses System.getenv for Lambda compatibility.
 */
public class JwtValidator {

    private final String secret;

    public JwtValidator() {
        this(System.getenv("JWT_SECRET"));
    }

    // Package-private for testing
    JwtValidator(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("Environment variable JWT_SECRET is not set");
        }
        this.secret = secret;
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(getSecretKey())
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public String extractUserId(String token) {
        return extractClaim(token, "id");
    }

    public String extractEmail(String token) {
        return extractClaim(token, "email");
    }

    public String extractUsername(String token) {
        return extractClaim(token, "username");
    }

    public Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSecretKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey getSecretKey() {
        byte[] keyBytes = Decoders.BASE64.decode(secret);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    String extractClaim(String token, String claim) {
        Claims claims = extractAllClaims(token);
        Object value = claims.get(claim);
        return value != null ? value.toString() : null;
    }
}
