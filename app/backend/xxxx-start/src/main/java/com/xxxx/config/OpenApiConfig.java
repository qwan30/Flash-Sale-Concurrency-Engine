package com.xxxx.config;

import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI flashSaleOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Flash-Sale Concurrency Engine API")
                        .version("1.0.0")
                        .description("REST API for flash-sale stock warmup, order creation, benchmark reset, and consistency checks."))
                .externalDocs(new ExternalDocumentation()
                        .description("Project README")
                        .url("https://github.com/qwan30/Flash-Sale-Concurrency-Engine#readme"));
    }

    @Bean
    public GroupedOpenApi labApi() {
        return GroupedOpenApi.builder()
                .group("lab-api")
                .packagesToScan("com.xxxx.ddd.controller.http")
                .build();
    }
}
