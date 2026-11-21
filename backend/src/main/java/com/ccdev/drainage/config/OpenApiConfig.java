package com.ccdev.drainage.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3 文档：Swagger UI 位于 /swagger-ui.html，
 * 文档描述符位于 /v3/api-docs。除 /api/auth/login 外接口均需 X-Auth-Token。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI drainageOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("排水管道结构缺陷普查评估平台 API")
                        .version("1.1.0")
                        .description("含承包商判读一致性数据服务：共同样本库、盲评配对、分层一致性、"
                                + "证据查询与版本化质量画像。"))
                .components(new Components().addSecuritySchemes("X-Auth-Token",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Auth-Token")))
                .addSecurityItem(new SecurityRequirement().addList("X-Auth-Token"));
    }
}
