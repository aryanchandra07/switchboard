package com.switchboard.gateway.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.switchboard.gateway.member.MemberService.InvalidMemberException;

class MemberLimitsValidationTest {

	private static MemberLimits limits(String daily, String monthly, int rpm, List<String> models) {
		return new MemberLimits(null, new BigDecimal(daily), new BigDecimal(monthly), rpm, models, Instant.now());
	}

	private static MemberLimits ok() {
		return limits("1.00", "20.00", 20, List.of("llama3.2"));
	}

	@Test
	void validLimits_pass() {
		assertThat(MemberService.validate(ok())).isNotNull();
		assertThat(MemberService.validate(limits("0", "0", 1, List.of()))).isNotNull(); // zero budget = blocked
		assertThat(MemberService.validate(limits("5", "5", 10000, List.of("a")))).isNotNull();
	}

	@Test
	void negativeBudget_fails() {
		assertThatThrownBy(() -> MemberService.validate(limits("-1", "20", 20, List.of())))
				.isInstanceOf(InvalidMemberException.class).hasMessageContaining("negative");
	}

	@Test
	void monthlyBelowDaily_fails() {
		assertThatThrownBy(() -> MemberService.validate(limits("5", "4.99", 20, List.of())))
				.isInstanceOf(InvalidMemberException.class).hasMessageContaining("monthly_budget_usd");
	}

	@Test
	void tooManyDecimals_fails_butTrailingZerosAreFine() {
		assertThatThrownBy(() -> MemberService.validate(limits("1.001", "20", 20, List.of())))
				.hasMessageContaining("2 decimal");
		assertThat(MemberService.validate(limits("1.500", "20.0000", 20, List.of()))).isNotNull();
	}

	@Test
	void hugeBudget_fails() {
		assertThatThrownBy(() -> MemberService.validate(limits("1", "10000.01", 20, List.of())))
				.hasMessageContaining("exceed");
	}

	@Test
	void rpmOutOfRange_fails() {
		assertThatThrownBy(() -> MemberService.validate(limits("1", "2", 0, List.of()))).hasMessageContaining("rpm");
		assertThatThrownBy(() -> MemberService.validate(limits("1", "2", 10001, List.of())))
				.hasMessageContaining("rpm");
	}

	@Test
	void modelNames_areTrimmedDedupedAndChecked() {
		var result = MemberService.validate(limits("1", "2", 5, List.of(" llama3.2 ", "llama3.2", "qwen2.5:7b",
				"claude-haiku-4.5")));
		assertThat(result.allowedModels()).containsExactly("llama3.2", "qwen2.5:7b", "claude-haiku-4.5");

		assertThatThrownBy(() -> MemberService.validate(limits("1", "2", 5, List.of("bad model"))))
				.hasMessageContaining("invalid model name");
		assertThatThrownBy(() -> MemberService.validate(limits("1", "2", 5, List.of(""))))
				.hasMessageContaining("invalid model name");
		assertThatThrownBy(() -> MemberService.validate(limits("1", "2", 5, Arrays.asList("ok", null))))
				.hasMessageContaining("invalid model name");
		assertThatThrownBy(() -> MemberService.validate(limits("1", "2", 5, List.of("x'; DROP TABLE users;--"))))
				.hasMessageContaining("invalid model name");
	}

	@Test
	void tooManyModels_fails() {
		assertThatThrownBy(() -> MemberService.validate(limits("1", "2", 5, Collections.nCopies(21, "m"))))
				.hasMessageContaining("at most 20");
	}
}
