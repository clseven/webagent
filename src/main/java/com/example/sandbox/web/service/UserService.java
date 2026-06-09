package com.example.sandbox.web.service;

import com.example.sandbox.web.model.entity.UserEntity;
import com.example.sandbox.web.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, BCryptPasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UserEntity register(String username, String password) {
        if (userRepository.findByUsername(username).isPresent()) {
            throw new RuntimeException("Username already exists");
        }

        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setToken(UUID.randomUUID().toString());
        user = userRepository.save(user);
        log.info("User registered: {}", username);
        return user;
    }

    @Transactional
    public UserEntity login(String username, String password) {
        UserEntity user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("Invalid username or password"));

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new RuntimeException("Invalid username or password");
        }

        user.regenerateToken();
        userRepository.save(user);
        log.info("User logged in: {}", username);
        return user;
    }

    public UserEntity validateToken(String token) {
        return userRepository.findByToken(token).orElse(null);
    }

    public UserEntity getById(Long id) {
        return userRepository.findById(id).orElse(null);
    }
}
