package com.aifanyi.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 从 SecurityContext 取当前登录用户。
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static AuthUser current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthUser u) {
            return u;
        }
        return null;
    }

    public static Long currentUserId() {
        AuthUser u = current();
        return u == null ? null : u.userId();
    }
}
