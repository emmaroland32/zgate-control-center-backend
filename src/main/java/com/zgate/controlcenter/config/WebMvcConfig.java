package com.zgate.controlcenter.config;

import com.zgate.controlcenter.security.StepUpInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the step-up check, which must see the resolved handler method to read its annotation. */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final StepUpInterceptor stepUpInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(stepUpInterceptor).addPathPatterns("/api/**");
    }
}
