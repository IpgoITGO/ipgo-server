package com.ipgo.server.config;

import com.ipgo.server.service.BillingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class BillingAccessConfig implements WebMvcConfigurer {
    private final BillingService billing;
    public BillingAccessConfig(BillingService b) { billing=b; }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
                String method=request.getMethod();
                if(("POST".equals(method) || "PUT".equals(method)) && request.getUserPrincipal()!=null)
                    billing.assertWriteAllowed(Long.parseLong(request.getUserPrincipal().getName()));
                return true;
            }
        }).addPathPatterns("/api/uploads","/api/replenishment-settings","/api/products/*/replenishment-settings",
                "/api/results/*/scenarios","/api/results/*/insight/regenerate","/api/insight-batches/*/results/*/generate");
    }
}
