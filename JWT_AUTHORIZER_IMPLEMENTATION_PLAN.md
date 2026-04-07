# Plan de Implementación: JWT Authorizer → API Gateway Lambda Authorizer

## Arquitectura

```
Cliente (con JWT)
       │
       ▼
┌─────────────────────────────────────────────────┐
│  API Gateway HTTP API                            │
│                                                  │
│  ┌───────────────────────────────────────────┐  │
│  │  Lambda Authorizer (Java 17)              │  │
│  │  1. Extrae token de "Authorization"       │  │
│  │  2. Valida JWT (HS256, secret compartido) │  │
│  │  3. Extrae claims: userId, email, username│  │
│  │  4. Retorna policy Allow + context        │  │
│  └───────────────────────────────────────────┘  │
│                                                  │
│  Integration Request Mapping:                    │
│  header.X-UserId ← $context.authorizer.userId    │
│  header.X-Email ← $context.authorizer.email      │
│  header.X-Username ← $context.authorizer.username│
└──────────────────────┬──────────────────────────┘
                       │ Headers inyectados por API Gateway
                       ▼
┌─────────────────────────────────────────────────┐
│  Spring Boot Backend                             │
│  ┌───────────────────────────────────────────┐  │
│  │  GatewayHeadersFilter                     │  │
│  │  1. Lee headers X-UserId, X-Email, etc.  │  │
│  │  2. Los inyecta en Reactor Context        │  │
│  │  3. El resto del código NO cambia         │  │
│  └───────────────────────────────────────────┘  │
└─────────────────────────────────────────────────┘
```

### Por qué es robusto

El cliente NO puede forjar los headers `X-UserId`, `X-Email`, `X-Username` porque API Gateway los inyecta después de que el authorizer valida el JWT. El backend confía en estos headers porque solo vienen de API Gateway.

---

## Repositorio 1: `app-microservice-location-authorizer` (nuevo)

### Archivos a crear

#### 1. `pom.xml`

Proyecto Maven independiente para la Lambda Authorizer.

**Dependencias:**
- `com.amazonaws:aws-lambda-java-core:1.2.3` — Runtime de Lambda
- `com.amazonaws:aws-lambda-java-events:3.11.0` — Tipos de eventos de API Gateway
- `io.jsonwebtoken:jjwt-api:0.12.6` — Validación JWT (misma versión que el backend)
- `io.jsonwebtoken:jjwt-impl:0.12.6` (runtime) — Implementación JWT
- `io.jsonwebtoken:jjwt-jackson:0.12.6` (runtime) — Serialización JWT
- `com.fasterxml.jackson.core:jackson-databind` — Parsear JSON

**Build:**
- `maven-compiler-plugin` con Java 17
- `maven-shade-plugin` para crear fat jar (Lambda necesita todas las dependencias empaquetadas)

#### 2. `src/main/java/com/brayanpv/authorizer/JwtAuthorizerHandler.java`

Handler principal de la Lambda.

**Responsabilidades:**
- Implementa `RequestStreamHandler`
- Recibe `APIGatewayV2CustomAuthorizerEvent` (formato v2 de HTTP API)
- Retorna `APIGatewayV2CustomAuthorizerResponse`

**Lógica:**
1. Parsea el evento JSON del request
2. Extrae el token del header `authorization` (lowercase en HTTP API v2)
3. Verifica si la ruta es pública:
   - `/app-microservice-location/landscapes/nearby` (GET)
   - `/app-microservice-location/landscapes/{uuid}/likes` (GET)
   - `/app-microservice-location/images/*`
   - `/app-microservice-location/landscapes/{uuid}` (GET)
4. Si es pública → retorna policy `Allow` sin validar token
5. Si no hay token → retorna 401
6. Valida JWT con `JwtValidator`:
   - Si válido → retorna policy `Allow` + context con `userId`, `email`, `username`
   - Si inválido/expirado → retorna 401

#### 3. `src/main/java/com/brayanpv/authorizer/JwtValidator.java`

Lógica de validación JWT (misma lógica que `JWTService` del backend).

**Métodos:**
- `validateToken(token, secret)` → `boolean`
  - Parsea el token con `Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token)`
  - Retorna `true` si no lanza excepción, `false` si lanza `JwtException`
- `extractClaim(token, secret, claimName)` → `String`
  - Extrae un claim específico del payload
  - Usa la misma lógica de Base64-decode + `Keys.hmacShaKeyFor()` que el backend

**Variables de entorno:**
- `JWT_SECRET` — La misma clave Base64 que usa el backend

#### 4. `template.yaml` (SAM Template)

Define la infraestructura en AWS.

**Recursos:**

```yaml
Resources:
  JwtAuthorizerFunction:
    Type: AWS::Serverless::Function
    Properties:
      Handler: com.brayanpv.authorizer.JwtAuthorizerHandler::handleRequest
      Runtime: java17
      MemorySize: 256
      Timeout: 10
      Environment:
        Variables:
          JWT_SECRET: !Ref JwtSecret
      Events:
        Authorizer:
          Type: HttpApi
          Properties:
            ApiId: !Ref HttpApi
            Method: "*"
            Path: "*"

  HttpApi:
    Type: AWS::Serverless::HttpApi
    Properties:
      Auth:
        DefaultAuthorizer: JwtAuthorizer
        Authorizers:
          JwtAuthorizer:
            FunctionArn: !GetAtt JwtAuthorizerFunction.Arn
            Identity:
              Headers:
                - Authorization
      Routes:
        # Rutas protegidas (con authorizer)
        POST /app-microservice-location/upload:
          Authorizer: JwtAuthorizer
        POST /app-microservice-location/landscapes/{id}/like:
          Authorizer: JwtAuthorizer
        DELETE /app-microservice-location/landscapes/{id}/like:
          Authorizer: JwtAuthorizer
        GET /app-microservice-location/landscapes/{id}/liked:
          Authorizer: JwtAuthorizer
        GET /app-microservice-location/landscapes/my:
          Authorizer: JwtAuthorizer
        # Rutas públicas (sin authorizer)
        GET /app-microservice-location/landscapes/nearby:
          Authorizer: NONE
        GET /app-microservice-location/landscapes/{id}/likes:
          Authorizer: NONE
        GET /app-microservice-location/images/{filename}:
          Authorizer: NONE
        GET /app-microservice-location/landscapes/{id}:
          Authorizer: NONE

Parameters:
  JwtSecret:
    Type: String
    NoEcho: true
    Description: JWT Secret Key (Base64 encoded)
```

---

## Repositorio 2: `app-microservice-location-app` (actual)

### Archivos a modificar

#### 5. `JWTAuthFilter.java` → `GatewayHeadersFilter.java`

**Cambios:**
- Eliminar toda la lógica de validación JWT
- Eliminar dependencia de `IJWTService`
- Eliminar método `unauthorized()`
- Eliminar método `isPublicPath()`
- Leer headers `X-UserId`, `X-Email`, `X-Username` del request
- Inyectarlos en Reactor Context (para que el resto del código funcione sin cambios)
- Si los headers no existen (desarrollo local sin API Gateway), permitir el paso con un warning log

**Código del nuevo filtro:**
```java
@Override
public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    if (exchange.getRequest().getMethod() == HttpMethod.OPTIONS) {
        return chain.filter(exchange);
    }

    String userId = exchange.getRequest().getHeaders().getFirst("X-UserId");
    String email = exchange.getRequest().getHeaders().getFirst("X-Email");
    String username = exchange.getRequest().getHeaders().getFirst("X-Username");

    if (userId == null) {
        log.warn("X-UserId header not found - running without API Gateway");
        return chain.filter(exchange);
    }

    return chain.filter(exchange)
            .contextWrite(ctx -> ctx
                    .put("userId", userId)
                    .put("email", email != null ? email : "")
                    .put("username", username != null ? username : "")
            );
}
```

#### 6. `SecurityConfig.java`

**Cambios:**
- Cambiar inyección de `JWTAuthFilter` a `GatewayHeadersFilter`
- Resto de la configuración permanece igual

```java
@Configuration
@EnableWebFluxSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final GatewayHeadersFilter gatewayHeadersFilter;

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .anyExchange().permitAll()
                )
                .addFilterBefore(gatewayHeadersFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }
}
```

#### 7. `application.yml`

**Agregar configuración de gateway:**
```yaml
gateway:
  auth:
    enabled: ${GATEWAY_AUTH_ENABLED:false}
    headers:
      user-id: X-UserId
      email: X-Email
      username: X-Username
```

#### 8. Archivos que se mantienen

- `JWTService.java` — Se mantiene para compatibilidad y posible rollback
- `IJWTService.java` — Se mantiene para compatibilidad

---

## Pasos de Implementación (Orden)

### Fase 1: Crear el Authorizer

| Paso | Acción | Archivos | Estado |
|------|--------|----------|--------|
| 1 | Crear proyecto Maven con arquetipo quickstart | `mvn archetype:generate` | ☐ |
| 2 | Actualizar `pom.xml` a Java 17 + dependencias | `pom.xml` | ☐ |
| 3 | Crear `JwtValidator.java` | `authorizer/.../JwtValidator.java` | ☐ |
| 4 | Crear `JwtAuthorizerHandler.java` | `authorizer/.../JwtAuthorizerHandler.java` | ☐ |
| 5 | Crear `template.yaml` (SAM) | `template.yaml` | ☐ |

### Fase 2: Adaptar el Backend

| Paso | Acción | Archivos | Estado |
|------|--------|----------|--------|
| 6 | Crear `GatewayHeadersFilter.java` | `component/filter/GatewayHeadersFilter.java` | ☐ |
| 7 | Actualizar `SecurityConfig.java` | `SecurityConfig.java` | ☐ |
| 8 | Agregar config de gateway | `application.yml` | ☐ |
| 9 | Eliminar `JWTAuthFilter.java` | `component/filter/JWTAuthFilter.java` | ☐ |

### Fase 3: Deploy

| Paso | Acción | Comando | Estado |
|------|--------|---------|--------|
| 10 | Instalar SAM CLI | `brew install aws-sam-cli` o descargar | ☐ |
| 11 | Configurar AWS CLI | `aws configure` | ☐ |
| 12 | Build del authorizer | `sam build` | ☐ |
| 13 | Deploy guiado (primera vez) | `sam deploy --guided` | ☐ |
| 14 | Deployes posteriores | `sam deploy` | ☐ |

### Fase 4: Test

| Paso | Acción | Estado |
|------|--------|--------|
| 15 | Test con token válido | ☐ |
| 16 | Test con token inválido | ☐ |
| 17 | Test con token expirado | ☐ |
| 18 | Test sin token en ruta protegida | ☐ |
| 19 | Test en ruta pública sin token | ☐ |
| 20 | Test end-to-end con backend | ☐ |

---

## Flujo de Deploy con SAM CLI

### Instalación

```bash
# macOS
brew install aws-sam-cli

# Linux
curl -LO "https://github.com/aws/aws-sam-cli/releases/latest/download/aws-sam-cli-linux-x86_64.zip"
unzip aws-sam-cli-linux-x86_64.zip -d sam-installation
sudo ./sam-installation/install
```

### Configuración

```bash
# Configurar credenciales de AWS
aws configure
# AWS Access Key ID: [tu access key]
# AWS Secret Access Key: [tu secret key]
# Default region name: us-east-1 (o tu región)
# Default output format: json
```

### Deploy

```bash
# En el directorio del authorizer:

# Primera vez (guiado)
sam deploy --guided
# Stack Name: location-authorizer
# AWS Region: us-east-1
# Parameter JwtSecret: [tu JWT_KEY en Base64]
# Confirm changes: Y
# Allow SAM CLI IAM role creation: Y

# Deployes posteriores
sam build
sam deploy
```

### Qué hace SAM

1. **`sam build`**: Compila el código Java con Maven, crea el fat jar
2. **`sam deploy`**: 
   - Empaqueta el JAR y lo sube a S3
   - Crea un CloudFormation stack con la infraestructura
   - Crea la Lambda function
   - Crea el API Gateway HTTP API
   - Configura el authorizer y las rutas
   - Retorna la URL del API Gateway

---

## Free Tier

| Servicio | Free Tier | Tu uso estimado |
|---|---|---|
| **API Gateway HTTP API** | 1M requests/mes (12 meses) | Depende de tu tráfico |
| **Lambda** | 1M requests/mes + 400,000 GB-segundos | Authorizer ~50ms × 256MB = ~12.5 GB-segundos por 1M requests |
| **Total** | **Gratis** para <1M requests/mes | |

---

## Estructura del Repositorio del Authorizer

```
app-microservice-location-authorizer/
├── pom.xml
├── template.yaml
├── README.md
├── .gitignore
└── src/
    └── main/
        ├── java/
        │   └── com/brayanpv/
        │       └── authorizer/
        │           ├── JwtAuthorizerHandler.java
        │           └── JwtValidator.java
        └── resources/
            └── (vacío o log4j2.xml si necesitas logging)
```

---

## Estructura del Repositorio del Backend (cambios)

```
app-microservice-location-app/
├── src/main/java/com/brayanpv/app/
│   ├── component/
│   │   ├── filter/
│   │   │   ├── JWTAuthFilter.java          ← ELIMINAR
│   │   │   └── GatewayHeadersFilter.java   ← CREAR
│   │   └── configuration/
│   │       └── SecurityConfig.java         ← MODIFICAR
│   └── service/
│       └── implementations/
│           └── JWTService.java             ← MANTENER (compatibilidad)
└── src/main/resources/
    └── application.yml                     ← MODIFICAR
```

---

## Variables de Entorno

### Lambda Authorizer

| Variable | Descripción | Ejemplo |
|---|---|---|
| `JWT_SECRET` | Clave secreta JWT (Base64) — debe ser la misma que en el backend | `MsUEV8IkLNB7...` |

### Backend Spring Boot

| Variable | Descripción | Ejemplo |
|---|---|---|
| `GATEWAY_AUTH_ENABLED` | Habilita/deshabilita la validación de headers del gateway | `true` en producción, `false` en local |

---

## Consideraciones de Seguridad

1. **JWT_SECRET compartido**: La misma clave Base64 debe estar en:
   - Lambda Authorizer (variable de entorno)
   - Backend Spring Boot (`JWT_KEY`)
   - Auth service (quien genera los tokens)

2. **Headers de confianza**: El backend confía en los headers `X-UserId`, `X-Email`, `X-Username` porque solo API Gateway puede inyectarlos. Si el backend es accesible directamente (sin pasar por API Gateway), estos headers podrían ser falsificados. **Solución**: El `GatewayHeadersFilter` permite el paso si los headers no existen (para desarrollo local), pero en producción el backend solo debería ser accesible a través de API Gateway.

3. **Rate limiting**: Configurar throttling en API Gateway para proteger contra abusos.

4. **CORS**: API Gateway maneja CORS. El `CorsConfig` del backend puede simplificarse o eliminarse si API Gateway maneja los headers CORS.

---

## Rollback

Si algo sale mal:

1. **Revertir cambios en el backend**:
   - Restaurar `JWTAuthFilter.java`
   - Revertir `SecurityConfig.java`
   - El `JWTService` se mantuvo, así que no hay problema

2. **Eliminar el stack de CloudFormation**:
   ```bash
   aws cloudformation delete-stack --stack-name location-authorizer
   ```

3. **El backend sigue funcionando** con su autenticación JWT original.
