package com.jobfinder.service;

import com.jobfinder.db.Database;
import com.jobfinder.dto.AuthDtos.AuthTokens;
import com.jobfinder.security.TokenService;
import com.jobfinder.web.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** Creates database backed refresh sessions. */
@Service
public class SessionService {

    private final Database db;
    private final TokenService tokens;

    public SessionService(Database db, TokenService tokens) {
        this.db = db;
        this.tokens = tokens;
    }

    /** Opens a new session and returns its token pair; retries once on an id collision. */
    public AuthTokens openSession(String userId) {
        for (int attempt = 0; attempt < 2; attempt++) {
            String sessionId = UUID.randomUUID().toString();
            TokenService.RefreshToken refresh = tokens.buildRefreshToken(userId, sessionId);
            try {
                if (db.fetchOne("auth/create_refresh_session",
                        sessionId, userId, refresh.jti(), refresh.expiresAt()) != null) {
                    return new AuthTokens(tokens.createAccessToken(userId, sessionId), refresh.token());
                }
                break;
            } catch (DataIntegrityViolationException collision) {
                // try again with a fresh id
            }
        }
        throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to create refresh session");
    }
}
