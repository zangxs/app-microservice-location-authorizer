package com.brayanpv.authorizer;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2CustomAuthorizerEvent;
import com.brayanpv.authorizer.models.response.APIGatewayV2CustomAuthorizerResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.Set;

/**
 * Lambda authorizer handler for API Gateway HTTP API (V2).
 *
 * <p>Flow:
 * <ol>
 *   <li>Parse incoming authorizer event from JSON stream</li>
 *   <li>Extract JWT from "Authorization" header</li>
 *   <li>If route is public → Allow without validation</li>
 *   <li>If no token → Deny (401)</li>
 *   <li>Validate JWT → Allow + context claims, or Deny (401)</li>
 * </ol>
 * </p>
 */
public class JwtAuthorizerHandler implements RequestStreamHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Public routes that don't require JWT validation
    private static final Set<String> PUBLIC_ROUTES = Set.of(
            "/app-microservice-location/landscapes/nearby",
            "/app-microservice-location/landscapes/{id}/likes",
            "/app-microservice-location/images/{filename}",
            "/app-microservice-location/landscapes/{id}"
    );

    private final JwtValidator jwtValidator;

    public JwtAuthorizerHandler() {
        this.jwtValidator = new JwtValidator();
    }

    // Visible for testing
    JwtAuthorizerHandler(JwtValidator jwtValidator) {
        this.jwtValidator = jwtValidator;
    }

    @Override
    public void handleRequest(InputStream inputStream, OutputStream outputStream, Context context) throws IOException {
        APIGatewayV2CustomAuthorizerEvent event = MAPPER.readValue(inputStream, APIGatewayV2CustomAuthorizerEvent.class);

        String routeKey = event.getRouteKey();
        String resourcePath = event.getRawPath();
        String routeArn = event.getRouteArn();
        String methodArn = routeArn != null ? routeArn : "";

        context.getLogger().log("Authorizer invoked: routeKey=" + routeKey + ", path=" + resourcePath);

        // Check if route is public
        if (isPublicRoute(resourcePath)) {
            context.getLogger().log("Public route, allowing without token validation: " + resourcePath);
            APIGatewayV2CustomAuthorizerResponse response =
                    APIGatewayV2CustomAuthorizerResponse.allow("public", methodArn, Map.of());
            MAPPER.writeValue(outputStream, response);
            return;
        }

        // Extract token from Authorization header
        String token = extractToken(event);
        if (token == null || token.isBlank()) {
            context.getLogger().log("No token provided for protected route: " + resourcePath);
            APIGatewayV2CustomAuthorizerResponse response =
                    APIGatewayV2CustomAuthorizerResponse.deny("anonymous", methodArn);
            MAPPER.writeValue(outputStream, response);
            return;
        }

        // Validate JWT
        if (!jwtValidator.validateToken(token)) {
            context.getLogger().log("Invalid or expired token for route: " + resourcePath);
            APIGatewayV2CustomAuthorizerResponse response =
                    APIGatewayV2CustomAuthorizerResponse.deny("unauthorized", methodArn);
            MAPPER.writeValue(outputStream, response);
            return;
        }

        // Extract claims and allow
        String userId = jwtValidator.extractUserId(token);
        String email = jwtValidator.extractEmail(token);
        String username = jwtValidator.extractUsername(token);

        context.getLogger().log("Token valid for userId=" + userId);

        Map<String, String> authorizerContext = Map.of(
                "userId", userId != null ? userId : "",
                "email", email != null ? email : "",
                "username", username != null ? username : ""
        );

        APIGatewayV2CustomAuthorizerResponse response =
                APIGatewayV2CustomAuthorizerResponse.allow(userId != null ? userId : "unknown", methodArn, authorizerContext);
        MAPPER.writeValue(outputStream, response);
    }

    private boolean isPublicRoute(String path) {
        if (path == null) return false;
        // Exact match (no path params)
        if (PUBLIC_ROUTES.contains(path)) return true;
        // Wildcard match for /images/*
        if (path.startsWith("/app-microservice-location/images/")) return true;
        // /app-microservice-location/landscapes/{id}/likes — but NOT /landscapes/my/likes
        if (path.matches("/app-microservice-location/landscapes/[^/]+/likes")) return true;
        // /app-microservice-location/landscapes/{id} — but NOT special endpoints like /my
        if (path.matches("/app-microservice-location/landscapes/[^/]+")) {
            String lastSegment = path.substring(path.lastIndexOf('/') + 1);
            if (!lastSegment.equals("my") && !lastSegment.equals("nearby")) {
                return true;
            }
        }
        return false;
    }

    private String extractToken(APIGatewayV2CustomAuthorizerEvent event) {
        // V2 HTTP API: headers are in routeKeyArn or headers map
        Map<String, String> headers = event.getHeaders();
        if (headers != null) {
            // Check lowercase "authorization" first (HTTP/2 normalizes to lowercase)
            String token = headers.get("authorization");
            if (token == null) {
                token = headers.get("Authorization");
            }
            if (token != null && token.startsWith("Bearer ")) {
                return token.substring(7);
            }
            return token;
        }
        return null;
    }
}
