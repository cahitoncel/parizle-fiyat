package com.parizle.prices.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "bist.bulletin")
public record BistBulletinProperties(
        @NotBlank String baseUrl,
        @Min(1) @Max(2) int session,
        @NotNull ZoneId timezone,
        @NotBlank String cron,
        boolean schedulingEnabled,
        @Min(1) int maxAttempts,
        @NotNull Duration retryDelay,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout
) {
}
