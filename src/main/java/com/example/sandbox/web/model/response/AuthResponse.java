package com.example.sandbox.web.model.response;

public class AuthResponse {

    private String token;
    private UserInfo user;

    public AuthResponse(String token, Long userId, String username) {
        this.token = token;
        this.user = new UserInfo(userId, username);
    }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public UserInfo getUser() { return user; }
    public void setUser(UserInfo user) { this.user = user; }

    public static class UserInfo {
        private Long id;
        private String username;

        public UserInfo(Long id, String username) {
            this.id = id;
            this.username = username;
        }

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
    }
}
