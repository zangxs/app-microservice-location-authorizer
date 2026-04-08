# app-microservice-location-authorizer
lambda authorizer

# Plan de Implementación: JWT Authorizer → API Gateway Lambda Authorizer

## Arquitectura

```
Cliente (con JWT)
       │
       ▼
┌──────────────────────────────────────────────────┐
│  API Gateway HTTP API                            │
│                                                  │
│  ┌───────────────────────────────────────────┐   │
│  │  Lambda Authorizer (Java 17)              │   │
│  │  1. Extrae token de "Authorization"       │   │
│  │  2. Valida JWT (HS256, secret compartido) │   │
│  │  3. Extrae claims: userId, email, username│   │ 
│  │  4. Retorna policy Allow + context        │   │
│  └───────────────────────────────────────────┘   │
│                                                  │
│  Integration Request Mapping:                    │
│  header.X-UserId ← $context.authorizer.userId    │
│  header.X-Email ← $context.authorizer.email      │
│  header.X-Username ← $context.authorizer.username│
└──────────────────────┬───────────────────────────┘
                       │ Headers inyectados por API Gateway
                       ▼
┌─────────────────────────────────────────────────┐
│  Spring Boot Backend                            │
│  ┌───────────────────────────────────────────┐  │
│  │  GatewayHeadersFilter                     │  │
│  │  1. Lee headers X-UserId, X-Email, etc.   │  │
│  │  2. Los inyecta en Reactor Context        │  │
│  │  3. El resto del código NO cambia         │  │
│  └───────────────────────────────────────────┘  │
└─────────────────────────────────────────────────┘
```


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

Lógica de validación JWT 

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


## Pasos de Implementación (Orden)

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
