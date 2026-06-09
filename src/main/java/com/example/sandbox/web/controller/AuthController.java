package com.example.sandbox.web.controller;

import com.example.sandbox.web.context.UserContext;
import com.example.sandbox.web.model.entity.UserEntity;
import com.example.sandbox.web.model.request.AuthRequest;
import com.example.sandbox.web.model.response.ApiResponse;
import com.example.sandbox.web.model.response.AuthResponse;
import com.example.sandbox.web.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private UserService userService;

    @PostMapping("/register")
    public ApiResponse<AuthResponse> register(@RequestBody AuthRequest request) {
        UserEntity user = userService.register(request.getUsername(), request.getPassword());
        AuthResponse resp = new AuthResponse(user.getToken(), user.getId(), user.getUsername());
        return ApiResponse.success(resp);
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@RequestBody AuthRequest request) {
        UserEntity user = userService.login(request.getUsername(), request.getPassword());
        AuthResponse resp = new AuthResponse(user.getToken(), user.getId(), user.getUsername());
        return ApiResponse.success(resp);
    }

    @GetMapping("/me")
    public ApiResponse<AuthResponse> me() {
        Long userId = UserContext.getCurrentUserId();
        UserEntity user = userService.getById(userId);
        if (user == null) {
            return ApiResponse.error(401, "User not found");
        }
        AuthResponse resp = new AuthResponse(user.getToken(), user.getId(), user.getUsername());
        return ApiResponse.success(resp);
    }
}
