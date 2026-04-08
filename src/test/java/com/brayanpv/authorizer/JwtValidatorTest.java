package com.brayanpv.authorizer;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.Before;
import org.junit.Test;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;

import static org.junit.Assert.*;

public class JwtValidatorTest {

    private static final String SECRET = Base64.getEncoder().encodeToString(
            "this-is-a-test-secret-key-for-jwt-validation-must-be-long-enough".getBytes()
    );

    private static final SecretKey KEY = Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET));

    private JwtValidator validator;

    @Before
    public void setUp() {
        validator = new JwtValidator(SECRET);
    }

    @Test
    public void validateToken_withValidToken_returnsTrue() {
        String token = buildToken("user-1", "test@example.com", "testuser", 3600);

        assertTrue(validator.validateToken(token));
    }

    @Test
    public void validateToken_withExpiredToken_returnsFalse() {
        String token = buildExpiredToken("user-1");

        assertFalse(validator.validateToken(token));
    }

    @Test
    public void validateToken_withInvalidSignature_returnsFalse() {
        String token = buildTokenWithDifferentSecret("user-1");

        assertFalse(validator.validateToken(token));
    }

    @Test
    public void validateToken_withMalformedToken_returnsFalse() {
        assertFalse(validator.validateToken("not-a-jwt"));
    }

    @Test
    public void validateToken_withEmptyToken_returnsFalse() {
        assertFalse(validator.validateToken(""));
    }

    @Test
    public void validateToken_withNullToken_returnsFalse() {
        assertFalse(validator.validateToken(null));
    }

    @Test
    public void extractUserId_returnsCorrectValue() {
        String token = buildToken("user-42", "test@example.com", "testuser", 3600);

        assertEquals("user-42", validator.extractUserId(token));
    }

    @Test
    public void extractEmail_returnsCorrectValue() {
        String token = buildToken("user-1", "john@example.com", "john", 3600);

        assertEquals("john@example.com", validator.extractEmail(token));
    }

    @Test
    public void extractUsername_returnsCorrectValue() {
        String token = buildToken("user-1", "test@example.com", "johndoe", 3600);

        assertEquals("johndoe", validator.extractUsername(token));
    }

    @Test
    public void extractClaim_returnsNullForMissingClaim() {
        String token = buildToken("user-1", "test@example.com", "testuser", 3600);

        assertNull(validator.extractClaim(token, "nonexistent"));
    }

    @Test
    public void extractAllClaims_returnsAllClaims() {
        String token = buildToken("user-99", "admin@test.com", "admin", 3600);

        var claims = validator.extractAllClaims(token);

        assertEquals("user-99", claims.get("id", String.class));
        assertEquals("admin@test.com", claims.get("email", String.class));
        assertEquals("admin", claims.get("username", String.class));
    }

    @Test(expected = IllegalStateException.class)
    public void constructor_throwsWhenSecretIsNull() {
        new JwtValidator((String) null);
    }

    @Test(expected = IllegalStateException.class)
    public void constructor_throwsWhenSecretIsBlank() {
        new JwtValidator("   ");
    }

    // -- Helpers --

    private String buildToken(String userId, String email, String username, int ttlSeconds) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + ttlSeconds * 1000L);

        return Jwts.builder()
                .subject(userId)
                .claim("id", userId)
                .claim("email", email)
                .claim("username", username)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(KEY)
                .compact();
    }

    private String buildExpiredToken(String userId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() - 3600 * 1000L);

        return Jwts.builder()
                .subject(userId)
                .claim("id", userId)
                .issuedAt(new Date(now.getTime() - 7200 * 1000L))
                .expiration(expiry)
                .signWith(KEY)
                .compact();
    }

    private String buildTokenWithDifferentSecret(String userId) {
        SecretKey otherKey = Keys.secretKeyFor(io.jsonwebtoken.SignatureAlgorithm.HS256);

        return Jwts.builder()
                .subject(userId)
                .claim("id", userId)
                .signWith(otherKey)
                .compact();
    }
}
