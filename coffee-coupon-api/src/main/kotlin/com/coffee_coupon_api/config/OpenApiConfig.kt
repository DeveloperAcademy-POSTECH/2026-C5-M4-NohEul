package com.coffee_coupon_api.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {

    @Bean
    fun openAPI(): OpenAPI =
        OpenAPI().info(
            Info()
                .title("Coffee Coupon API")
                .description("커피 쿠폰 발급 서비스 API")
                .version("v0.0.1"),
        )
}
