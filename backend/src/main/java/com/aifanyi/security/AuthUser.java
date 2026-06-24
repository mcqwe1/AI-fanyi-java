package com.aifanyi.security;

/**
 * 已认证用户主体，存入 SecurityContext 的 principal。
 */
public record AuthUser(Long userId, String username) {
}
