package com.mohan.payments.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3 description of the merchant API, served at {@code /v3/api-docs} and browsable at
 * {@code /swagger-ui.html}.
 * <p>
 * Only the things springdoc cannot infer are declared here: the bearer-token scheme and the API summary.
 * Paths, request and response shapes, and the validation rules all come from the controllers and the DTO
 * records, so the published schema cannot drift from what the code enforces. The {@code Idempotency-Key}
 * header is picked up automatically because the controller declares it as a {@code @RequestHeader} parameter.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI paymentsOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Payments API")
                        .version("v1")
                        .description("""
                                Merchant-facing payment API.

                                **Money** is an integer in minor units: `1050` means 10.50, with a separate \
                                ISO 4217 `currency`.

                                **Authentication**: `POST /api/v1/auth/login` returns a bearer token; every \
                                other endpoint requires `Authorization: Bearer <token>`. The merchant is always \
                                taken from the verified token, never from the path or body.

                                **Idempotency**: create, capture and refund require an `Idempotency-Key` header. \
                                Retrying with the same key replays the stored response rather than repeating the \
                                operation. Reusing a key for a different request is rejected with 422.

                                **Errors** use RFC 7807 problem details with two extra members: a stable `code` \
                                and the request's `correlationId`.

                                **Declines are not errors**: a refused card returns 201 with the payment in \
                                status FAILED. A processor outage returns 503 and stores nothing.
                                """))
                .addServersItem(new Server().url("/").description("This instance"))
                .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Access token from POST /api/v1/auth/login")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
