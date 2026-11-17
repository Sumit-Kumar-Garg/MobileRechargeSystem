package com.app.recharge.common;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the API's JWT bearer auth in the OpenAPI document so Swagger UI shows an "Authorize" button.
 * Applied globally; the public endpoints (GET /v1/plans, /v1/demo/**) simply ignore the header.
 * The docs themselves are only served when springdoc is enabled (demo profile or OPENAPI_ENABLED=true).
 */
@Configuration
public class OpenApiConfig {

    static final String BEARER = "bearerAuth";

    /** The OpenAPI document header: title, version, and a global JWT bearer security scheme. */
    @Bean
    OpenAPI rechargeOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Recharge API")
                        .version("v1")
                        .description("In the demo profile, get a token from POST /v1/demo/token and paste its "
                                + "accessToken into Authorize (without the \"Bearer \" prefix)."))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
