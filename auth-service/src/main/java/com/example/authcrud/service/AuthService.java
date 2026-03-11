package com.example.authcrud.service;

import com.example.authcrud.dto.AuthResponse;
import com.example.authcrud.dto.LoginRequest;
import com.example.authcrud.dto.RefreshTokenRequest;
import com.example.authcrud.dto.RegisterRequest;
import com.example.authcrud.entity.AppUser;
import com.example.authcrud.entity.Role;
import com.example.authcrud.repository.AppUserRepository;
import com.example.authcrud.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class AuthService {

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final CustomUserDetailsService userDetailsService;
    private final JwtService jwtService;
    private final RefreshTokenStoreService refreshTokenStoreService;
    private final LoginAttemptService loginAttemptService;

    public AuthService(AppUserRepository appUserRepository,
                       PasswordEncoder passwordEncoder,
                       AuthenticationManager authenticationManager,
                       CustomUserDetailsService userDetailsService,
                       JwtService jwtService,
                       RefreshTokenStoreService refreshTokenStoreService,
                       LoginAttemptService loginAttemptService) {
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.userDetailsService = userDetailsService;
        this.jwtService = jwtService;
        this.refreshTokenStoreService = refreshTokenStoreService;
        this.loginAttemptService = loginAttemptService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String username = normalize(request.getUsername());

        if (appUserRepository.existsByUsername(username)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
        }

        AppUser user = new AppUser();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRole(Role.USER);

        appUserRepository.save(user);

        UserDetails userDetails = userDetailsService.loadUserByUsername(username);
        return generateTokenPair(userDetails);
    }

    public AuthResponse login(LoginRequest request, String clientKey) {
        String username = normalize(request.getUsername());
        String loginClientKey = normalizeClientKey(clientKey);

        if (loginAttemptService.isLocked(username, loginClientKey)) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Too many failed login attempts. Please try again later."
            );
        }

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, request.getPassword())
            );
        } catch (AuthenticationException ex) {
            boolean lockedNow = loginAttemptService.recordFailedAttempt(username, loginClientKey);
            if (lockedNow) {
                throw new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "Too many failed login attempts. Please try again later."
                );
            }
            throw ex;
        }

        loginAttemptService.clearAttempts(username, loginClientKey);

        UserDetails userDetails = userDetailsService.loadUserByUsername(username);
        return generateTokenPair(userDetails);
    }

    public AuthResponse refresh(RefreshTokenRequest request) {
        String refreshToken = request.getRefreshToken().trim();
        if (!jwtService.isRefreshToken(refreshToken)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        }

        String username;
        String tokenId;
        try {
            username = jwtService.extractUsername(refreshToken);
            tokenId = jwtService.extractTokenId(refreshToken);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        }

        if (tokenId == null || !refreshTokenStoreService.isActive(username, tokenId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        }

        UserDetails userDetails = userDetailsService.loadUserByUsername(username);
        if (!jwtService.isTokenValid(refreshToken, userDetails)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        }
        refreshTokenStoreService.revoke(username, tokenId);
        return generateTokenPair(userDetails);
    }

    public void logout(RefreshTokenRequest request) {
        String refreshToken = request.getRefreshToken().trim();
        if (!jwtService.isRefreshToken(refreshToken)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        }

        String username;
        String tokenId;
        try {
            username = jwtService.extractUsername(refreshToken);
            tokenId = jwtService.extractTokenId(refreshToken);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        }

        if (tokenId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        }

        refreshTokenStoreService.revoke(username, tokenId);
    }

    private String normalize(String value) {
        return value == null ? null : value.trim();
    }

    private String normalizeClientKey(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.trim();
    }

    private AuthResponse generateTokenPair(UserDetails userDetails) {
        String accessToken = jwtService.generateAccessToken(userDetails);
        String refreshTokenId = java.util.UUID.randomUUID().toString();
        String refreshToken = jwtService.generateRefreshToken(userDetails, refreshTokenId);
        refreshTokenStoreService.store(userDetails.getUsername(), refreshTokenId, jwtService.getRefreshExpirationMs());
        return new AuthResponse(accessToken, refreshToken);
    }
}
