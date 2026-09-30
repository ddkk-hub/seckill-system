package com.ddk.seckill.controller;

import com.ddk.seckill.service.AuthService;
import jakarta.servlet.http.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
@ConditionalOnProperty(name="seckill.auth.enabled", havingValue="true")
public class AuthWebConfiguration implements WebMvcConfigurer {
    public static final String USER_ATTRIBUTE=AuthWebConfiguration.class.getName()+".userId";
    private final AuthService auth;
    public AuthWebConfiguration(AuthService auth,Environment env) {
        this.auth=auth;
        if(!"async".equals(env.getProperty("seckill.mode")) || !env.getProperty("seckill.protection.enabled",Boolean.class,false)
                || !env.getProperty("seckill.engineering.enabled",Boolean.class,false))
            throw new IllegalStateException("Authentication requires async, protection and engineering modes");
    }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
                response.setHeader("Cache-Control","no-store");
                String path=request.getRequestURI().substring(request.getContextPath().length());
                boolean publicRead="GET".equals(request.getMethod()) &&
                    (path.matches("/product/[0-9]+") || path.equals("/test") || path.equals("/actuator/health") ||
                     path.equals("/actuator/health/liveness") || path.equals("/actuator/health/readiness"));
                boolean credentials="POST".equals(request.getMethod()) &&
                    (path.equals("/auth/register") || path.equals("/auth/login"));
                if(!publicRead && !credentials) {
                    try {request.setAttribute(USER_ATTRIBUTE,auth.authenticate(request.getHeader("Authorization")));}
                    catch(org.springframework.web.server.ResponseStatusException e) {
                        if(e.getStatusCode().value()==401)response.setHeader("WWW-Authenticate","Bearer");
                        throw e;
                    }
                }
                return true;
            }
        }).addPathPatterns("/**");
    }
    public static long user(HttpServletRequest request) {
        Object value=request.getAttribute(USER_ATTRIBUTE);
        if(!(value instanceof Long id))throw AuthService.unauthorized();
        return id;
    }
}
