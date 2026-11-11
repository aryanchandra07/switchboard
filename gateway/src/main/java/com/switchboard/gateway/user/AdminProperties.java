package com.switchboard.gateway.user;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** First-admin credentials from configuration (prefix "switchboard.admin"). */
@ConfigurationProperties(prefix = "switchboard.admin")
public record AdminProperties(String email, String password) {

	public boolean isConfigured() {
		return email != null && !email.isBlank() && password != null && !password.isBlank();
	}
}
