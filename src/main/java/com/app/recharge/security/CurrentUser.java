package com.app.recharge.security;

import com.app.recharge.common.BusinessException;
import com.app.recharge.common.ErrorCode;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/** Identity always comes from the validated token, never from request parameters or bodies. */
public final class CurrentUser {

    private CurrentUser() { }

    /** The caller's user id: the token's subject parsed as a UUID. Throws UNAUTHENTICATED if it is not a UUID. */
    public static UUID id(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "Invalid token subject");
        }
    }
}
