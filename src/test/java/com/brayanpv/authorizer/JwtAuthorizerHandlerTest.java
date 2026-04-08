package com.brayanpv.authorizer;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class JwtAuthorizerHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private JwtValidator mockValidator;

    @Mock
    private Context mockContext;

    @Mock
    private LambdaLogger mockLogger;

    private JwtAuthorizerHandler handler;

    @Before
    public void setUp() {
        when(mockContext.getLogger()).thenReturn(mockLogger);
        handler = new JwtAuthorizerHandler(mockValidator);
    }

    // -- Public route tests --

    @Test
    public void handleRequest_publicRouteNearby_allowsWithoutToken() throws IOException {
        String eventJson = buildEvent("/app-microservice-location/landscapes/nearby", "GET", null);

        String responseJson = invokeHandler(eventJson);
        JsonNode response = MAPPER.readTree(responseJson);

        assertEquals("Allow", response.get("policyDocument").get("Statement").get(0).get("Effect").asText());
        assertEquals("public", response.get("principalId").asText());
        verifyNoInteractions(mockValidator);
    }

    @Test
    public void handleRequest_publicRouteWithIdParam_allowsWithoutToken() throws IOException {
        String eventJson = buildEvent("/app-microservice-location/landscapes/abc-123", "GET", null);

        String responseJson = invokeHandler(eventJson);
        JsonNode response = MAPPER.readTree(responseJson);

        assertEquals("Allow", response.get("policyDocument").get("Statement").get(0).get("Effect").asText());
        verifyNoInteractions(mockValidator);
    }

    @Test
    public void handleRequest_imageRouteWildcard_allowsWithoutToken() throws IOException {
        String eventJson = buildEvent("/app-microservice-location/images/photo.jpg", "GET", null);

        String responseJson = invokeHandler(eventJson);
        JsonNode response = MAPPER.readTree(responseJson);

        assertEquals("Allow", response.get("policyDocument").get("Statement").get(0).get("Effect").asText());
        verifyNoInteractions(mockValidator);
    }

    @Test
    public void handleRequest_likesRoute_allowsWithoutToken() throws IOException {
        String eventJson = buildEvent("/app-microservice-location/landscapes/xyz/likes", "GET", null);

        String responseJson = invokeHandler(eventJson);
        JsonNode response = MAPPER.readTree(responseJson);

        assertEquals("Allow", response.get("policyDocument").get("Statement").get(0).get("Effect").asText());
        verifyNoInteractions(mockValidator);
    }

    // -- Protected route: no token --

    @Test
    public void handleRequest_protectedRoute_noToken_denies() throws IOException {
        String eventJson = buildEvent("/app-microservice-location/upload", "POST", null);

        String responseJson = invokeHandler(eventJson);
        JsonNode response = MAPPER.readTree(responseJson);

        assertEquals("Deny", response.get("policyDocument").get("Statement").get(0).get("Effect").asText());
        assertEquals("anonymous", response.get("principalId").asText());
        assertNull(response.get("context"));
    }

    @Test
    public void handleRequest_protectedRoute_emptyToken_denies() throws IOException {
        String eventJson = buildEvent("/app-microservice-location/landscapes/my", "GET", "");

        String responseJson = invokeHandler(eventJson);
        JsonNode response = MAPPER.readTree(responseJson);

        assertEquals("Deny", response.get("policyDocument").get("Statement").get(0).get("Effect").asText());
    }

    // -- Protected route: invalid token --

    @Test
    public void handleRequest_protectedRoute_invalidToken_denies() throws IOException {
        when(mockValidator.validateToken("invalid-token")).thenReturn(false);

        String eventJson = buildEvent("/app-microservice-location/upload", "POST", "Bearer invalid-token");

        String responseJson = invokeHandler(eventJson);
        JsonNode response = MAPPER.readTree(responseJson);

        assertEquals("Deny", response.get("policyDocument").get("Statement").get(0).get("Effect").asText());
        assertEquals("unauthorized", response.get("principalId").asText());
        assertNull(response.get("context"));
        verify(mockValidator).validateToken("invalid-token");
    }

    // -- Protected route: valid token --

    @Test
    public void handleRequest_protectedRoute_validToken_allowsWithContext() throws IOException {
        String token = "valid.jwt.token";
        when(mockValidator.validateToken(token)).thenReturn(true);
        when(mockValidator.extractUserId(token)).thenReturn("user-42");
        when(mockValidator.extractEmail(token)).thenReturn("test@example.com");
        when(mockValidator.extractUsername(token)).thenReturn("testuser");

        String eventJson = buildEvent("/app-microservice-location/upload", "POST", "Bearer " + token);

        String responseJson = invokeHandler(eventJson);
        JsonNode response = MAPPER.readTree(responseJson);

        assertEquals("Allow", response.get("policyDocument").get("Statement").get(0).get("Effect").asText());
        assertEquals("user-42", response.get("principalId").asText());

        JsonNode context = response.get("context");
        assertNotNull(context);
        assertEquals("user-42", context.get("userId").asText());
        assertEquals("test@example.com", context.get("email").asText());
        assertEquals("testuser", context.get("username").asText());
    }

    @Test
    public void handleRequest_protectedRoute_validToken_myLandscapes_allows() throws IOException {
        String token = "valid.token.here";
        when(mockValidator.validateToken(token)).thenReturn(true);
        when(mockValidator.extractUserId(token)).thenReturn("user-7");
        when(mockValidator.extractEmail(token)).thenReturn("a@b.com");
        when(mockValidator.extractUsername(token)).thenReturn("alice");

        String eventJson = buildEvent("/app-microservice-location/landscapes/my", "GET", "Bearer " + token);

        String responseJson = invokeHandler(eventJson);
        JsonNode response = MAPPER.readTree(responseJson);

        assertEquals("Allow", response.get("policyDocument").get("Statement").get(0).get("Effect").asText());
    }

    @Test
    public void handleRequest_protectedRoute_validToken_likeEndpoint_allows() throws IOException {
        String token = "another.valid.token";
        when(mockValidator.validateToken(token)).thenReturn(true);
        when(mockValidator.extractUserId(token)).thenReturn("user-1");
        when(mockValidator.extractEmail(token)).thenReturn("x@y.com");
        when(mockValidator.extractUsername(token)).thenReturn("bob");

        String eventJson = buildEvent("/app-microservice-location/landscapes/123/like", "POST", "Bearer " + token);

        String responseJson = invokeHandler(eventJson);
        JsonNode response = MAPPER.readTree(responseJson);

        assertEquals("Allow", response.get("policyDocument").get("Statement").get(0).get("Effect").asText());
    }

    // -- Token format tests --

    @Test
    public void handleRequest_tokenWithoutBearerPrefix_stillValidates() throws IOException {
        String token = "bare-token-no-prefix";
        when(mockValidator.validateToken(token)).thenReturn(true);
        when(mockValidator.extractUserId(token)).thenReturn("user-1");
        when(mockValidator.extractEmail(token)).thenReturn("a@b.com");
        when(mockValidator.extractUsername(token)).thenReturn("alice");

        String eventJson = buildEvent("/app-microservice-location/upload", "POST", token);

        String responseJson = invokeHandler(eventJson);
        JsonNode response = MAPPER.readTree(responseJson);

        assertEquals("Allow", response.get("policyDocument").get("Statement").get(0).get("Effect").asText());
    }

    @Test
    public void handleRequest_lowercaseAuthorizationHeader_extractsToken() throws IOException {
        String token = "lowercase-header-token";
        when(mockValidator.validateToken(token)).thenReturn(true);
        when(mockValidator.extractUserId(token)).thenReturn("user-1");
        when(mockValidator.extractEmail(token)).thenReturn("a@b.com");
        when(mockValidator.extractUsername(token)).thenReturn("alice");

        String eventJson = buildEventWithLowercaseAuth("/app-microservice-location/upload", "Bearer " + token);

        String responseJson = invokeHandler(eventJson);
        JsonNode response = MAPPER.readTree(responseJson);

        assertEquals("Allow", response.get("policyDocument").get("Statement").get(0).get("Effect").asText());
    }

    // -- Helpers --

    private String buildEvent(String path, String method, String authHeader) {
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"type\":\"REQUEST\",");
        json.append("\"routeArn\":\"arn:aws:execute-api:us-east-1:123456789:api/*/").append(method).append(path).append("\",");
        json.append("\"routeKey\":\"").append(method).append(" ").append(path).append("\",");
        json.append("\"rawPath\":\"").append(path).append("\",");
        json.append("\"headers\":{");
        if (authHeader != null) {
            json.append("\"Authorization\":\"").append(authHeader).append("\"");
        }
        json.append("}");
        json.append("}");
        return json.toString();
    }

    private String buildEventWithLowercaseAuth(String path, String authHeader) {
        return "{"
                + "\"type\":\"REQUEST\","
                + "\"routeArn\":\"arn:aws:execute-api:us-east-1:123456789:api/*/GET" + path + "\","
                + "\"routeKey\":\"GET " + path + "\","
                + "\"rawPath\":\"" + path + "\","
                + "\"headers\":{\"authorization\":\"" + authHeader + "\"}"
                + "}";
    }

    private String invokeHandler(String eventJson) throws IOException {
        ByteArrayInputStream input = new ByteArrayInputStream(eventJson.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        handler.handleRequest(input, output, mockContext);

        return output.toString(StandardCharsets.UTF_8);
    }
}
