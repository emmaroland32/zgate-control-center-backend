package com.zgate.controlcenter.config;

import com.zgate.controlcenter.security.RateLimitInterceptor;
import com.zgate.controlcenter.security.StepUpInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the checks that must see the resolved handler method to read its annotation. */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final StepUpInterceptor stepUpInterceptor;
    private final RateLimitInterceptor rateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Rate limit FIRST: a throttled request must not go on to consume the more expensive
        // checks behind it, and step-up failures are themselves worth throttling.
        registry.addInterceptor(rateLimitInterceptor).addPathPatterns("/api/**");
        registry.addInterceptor(stepUpInterceptor).addPathPatterns("/api/**");
    }
}
