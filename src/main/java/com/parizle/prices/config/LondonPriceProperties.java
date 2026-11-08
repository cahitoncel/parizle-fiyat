package com.parizle.prices.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "london.prices")
public record LondonPriceProperties(
        @NotBlank String baseUrl,
        @NotBlank String userAgent,
        @NotNull ZoneId timezone,
        @NotBlank String cron,
        boolean schedulingEnabled,
        @Min(1) int maxAttempts,
        @NotNull Duration retryDelay,
        @NotNull Duration readTimeout
) {
}
