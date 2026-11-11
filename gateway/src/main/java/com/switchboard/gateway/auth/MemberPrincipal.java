package com.switchboard.gateway.auth;

import java.util.UUID;

/**
 * Who is calling /v1. {@code apiKeyId} is set when an sb- key was used, and null when the
 * web login token was used (e.g. from the Playground).
 */
public record MemberPrincipal(UUID userId, UUID apiKeyId, String role) {
}
