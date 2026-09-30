package com.ddk.seckill.controller;

import com.ddk.seckill.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@ConditionalOnProperty(name="seckill.auth.enabled", havingValue="true")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth){this.auth=auth;}
    @PostMapping("/register") public ResponseEntity<AuthService.Identity> register(@RequestBody AuthService.Credentials credentials,HttpServletRequest request) {
        return ResponseEntity.status(201).body(auth.register(credentials,request.getRemoteAddr()));
    }
    @PostMapping("/login") public AuthService.Session login(@RequestBody AuthService.Credentials credentials,HttpServletRequest request) {
        return auth.login(credentials,request.getRemoteAddr());
    }
    @GetMapping("/me") public java.util.Map<String,Long> me(HttpServletRequest request) {
        return java.util.Map.of("userId",AuthWebConfiguration.user(request));
    }
    @PostMapping("/logout") public ResponseEntity<Void> logout(HttpServletRequest request) {
        auth.logout(request.getHeader("Authorization"));return ResponseEntity.noContent().build();
    }
}
