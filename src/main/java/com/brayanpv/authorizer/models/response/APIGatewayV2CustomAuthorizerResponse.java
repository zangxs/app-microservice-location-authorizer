package com.brayanpv.authorizer.models.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Response model for API Gateway HTTP API (V2) custom authorizer.
 *
 * <p>Format expected by API Gateway:</p>
 * <pre>
 * {
 *   "principalId": "user-id",
 *   "policyDocument": {
 *     "Version": "2012-10-17",
 *     "Statement": [{ "Action": "execute-api:Invoke", "Effect": "Allow|Deny", "Resource": "arn:..." }]
 *   },
 *   "context": { "userId": "...", "email": "...", "username": "..." }
 * }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class APIGatewayV2CustomAuthorizerResponse {

    private final String principalId;
    private final PolicyDocument policyDocument;
    private final Map<String, String> context;

    public APIGatewayV2CustomAuthorizerResponse(String principalId, PolicyDocument policyDocument, Map<String, String> context) {
        this.principalId = principalId;
        this.policyDocument = policyDocument;
        this.context = context;
    }

    public String getPrincipalId() {
        return principalId;
    }

    public PolicyDocument getPolicyDocument() {
        return policyDocument;
    }

    public Map<String, String> getContext() {
        return context;
    }

    // -- Nested types --

    public static class PolicyDocument {
        private final String version;
        private final List<Statement> statement;

        public PolicyDocument(String version, List<Statement> statement) {
            this.version = version;
            this.statement = statement;
        }

        @JsonProperty("Version")
        public String getVersion() {
            return version;
        }

        @JsonProperty("Statement")
        public List<Statement> getStatement() {
            return statement;
        }
    }

    public static class Statement {
        private final String action;
        private final String effect;
        private final String resource;

        public Statement(String action, String effect, String resource) {
            this.action = action;
            this.effect = effect;
            this.resource = resource;
        }

        @JsonProperty("Action")
        public String getAction() {
            return action;
        }

        @JsonProperty("Effect")
        public String getEffect() {
            return effect;
        }

        @JsonProperty("Resource")
        public String getResource() {
            return resource;
        }
    }

    // -- Factory helpers --

    public static APIGatewayV2CustomAuthorizerResponse allow(String principalId, String resource, Map<String, String> context) {
        return new APIGatewayV2CustomAuthorizerResponse(
                principalId,
                new PolicyDocument("2012-10-17", List.of(new Statement("execute-api:Invoke", "Allow", resource))),
                context
        );
    }

    public static APIGatewayV2CustomAuthorizerResponse deny(String principalId, String resource) {
        return new APIGatewayV2CustomAuthorizerResponse(
                principalId,
                new PolicyDocument("2012-10-17", List.of(new Statement("execute-api:Invoke", "Deny", resource))),
                null
        );
    }
}
