package com.example.sandbox.web.config;

import com.example.sandbox.web.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.sandbox.web.context.UserContext;
import com.example.sandbox.web.exception.UnauthorizedException;
import com.example.sandbox.web.model.entity.UserEntity;
import com.example.sandbox.web.model.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

@Component
public class AuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthFilter.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final UserRepository userRepository;

    public AuthFilter(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();

        if (path.equals("/api/auth/login") || path.equals("/api/auth/register")) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!path.startsWith("/api/")) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            String authHeader = request.getHeader("Authorization");
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                throw new UnauthorizedException("Missing or invalid Authorization header");
            }

            String token = authHeader.substring(7);
            Optional<UserEntity> userOpt = userRepository.findByToken(token);
            if (userOpt.isEmpty()) {
                throw new UnauthorizedException("Invalid token");
            }

            UserContext.setCurrentUserId(userOpt.get().getId());
            filterChain.doFilter(request, response);
        } catch (UnauthorizedException e) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(
                    ApiResponse.error(401, e.getMessage())));
        } finally {
            UserContext.clear();
        }
    }
}
