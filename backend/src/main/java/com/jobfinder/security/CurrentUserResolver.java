package com.jobfinder.security;

import com.jobfinder.config.AppSettings;
import com.jobfinder.service.UserAccountService;
import com.jobfinder.web.ApiException;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.Map;

/** Authenticates the bearer token and injects {@link CurrentUser} into controller methods. */
@Component
public class CurrentUserResolver implements HandlerMethodArgumentResolver {

    private final AppSettings settings;
    private final TokenService tokens;
    private final UserAccountService users;

    public CurrentUserResolver(AppSettings settings, TokenService tokens, UserAccountService users) {
        this.settings = settings;
        this.tokens = tokens;
        this.users = users;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == CurrentUser.class;
    }

    @Override
    public CurrentUser resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                                       NativeWebRequest request, WebDataBinderFactory factory) {
        if (settings.devAutoLogin()) {
            return new CurrentUser(users.ensureDevUser());
        }

        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.regionMatches(true, 0, "bearer ", 0, 7)
                || authorization.substring(7).isBlank()) {
            throw unauthorized("Not authenticated");
        }

        Map<String, Object> claims;
        try {
            claims = tokens.decode(authorization.substring(7).strip());
        } catch (InvalidTokenException error) {
            throw unauthorized("Invalid token");
        }
        if (!"access".equals(claims.get("type"))) {
            throw unauthorized("Invalid token type");
        }

        Map<String, Object> user = users.findProfile(String.valueOf(claims.get("sub")));
        if (user == null) {
            throw unauthorized("User not found");
        }
        if (user.get("email_verified_at") == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Email not verified");
        }
        return new CurrentUser(user);
    }

    private static ApiException unauthorized(String detail) {
        return new ApiException(HttpStatus.UNAUTHORIZED, detail);
    }
}
