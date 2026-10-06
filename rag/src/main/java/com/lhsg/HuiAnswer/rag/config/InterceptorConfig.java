package com.lhsg.HuiAnswer.rag.config;

import com.lhsg.HuiAnswer.rag.admin.intercepter.KnowledgeInterceptor;
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

    // TODO 刷新拦截器范围考虑
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(knowledgeInterceptor)
                .addPathPatterns("/api/knowledge-base/**")
                .order(1);
        registry.addInterceptor(loginReflushInterceptor)
                .addPathPatterns("/api/knowledge-base/**")
                .order(10);
    }
}
