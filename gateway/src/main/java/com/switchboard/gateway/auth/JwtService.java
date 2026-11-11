package com.switchboard.gateway.auth;

import java.time.Clock;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import com.switchboard.gateway.user.User;

/**
 * Creates signed login tokens (JWTs). A JWT is a small JSON document (who you are, your role,
 * when it expires) plus a signature made with our secret, so nobody can forge or edit one.
 */
@Service
public class JwtService {

	static final String ISSUER = "switchboard";
	static final String ROLE_CLAIM = "role";
	static final String EMAIL_CLAIM = "email";

	private final JwtEncoder encoder;
	private final JwtProperties properties;
	private final Clock clock;

	@Autowired
	public JwtService(JwtEncoder encoder, JwtProperties properties) {
		this(encoder, properties, Clock.systemUTC());
	}

	JwtService(JwtEncoder encoder, JwtProperties properties, Clock clock) {
		this.encoder = encoder;
		this.properties = properties;
		this.clock = clock;
	}

	public IssuedToken issue(User user) {
		Instant now = clock.instant();
		Instant expiresAt = now.plus(properties.ttl());
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(ISSUER)
				.subject(user.id().toString())
				.issuedAt(now)
				.expiresAt(expiresAt)
				.claim(EMAIL_CLAIM, user.email())
				.claim(ROLE_CLAIM, user.role().name())
				.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
		return new IssuedToken(token, properties.ttl().toSeconds());
	}

	public record IssuedToken(String value, long expiresInSeconds) {
	}
}
