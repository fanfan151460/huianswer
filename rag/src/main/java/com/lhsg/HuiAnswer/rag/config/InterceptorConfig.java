package com.lhsg.HuiAnswer.rag.config;

import com.lhsg.HuiAnswer.rag.admin.intercepter.KnowledgeInterceptor;
import com.lhsg.HuiAnswer.rag.admin.intercepter.LoginInterceptor;
import com.lhsg.HuiAnswer.rag.admin.intercepter.LoginReflushInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class InterceptorConfig implements WebMvcConfigurer {

    private final KnowledgeInterceptor knowledgeInterceptor;
    private final LoginReflushInterceptor loginReflushInterceptor;
    private final LoginInterceptor loginInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(knowledgeInterceptor)
                .addPathPatterns("/api/knowledge-base/**")
                .order(1);
        registry.addInterceptor(loginReflushInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/api/hui-answer/login")
                .excludePathPatterns("/api/hui-answer/register")
                .order(10);
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/api/knowledge-base/**")
                .excludePathPatterns("/api/hui-answer/login")
                .excludePathPatterns("/api/hui-answer/register")
                .order(5);
    }
}
