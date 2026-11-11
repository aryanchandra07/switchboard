package com.switchboard.gateway.member;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.switchboard.gateway.member.MemberService.LimitsUpdate;
import com.switchboard.gateway.member.MemberService.Member;
import com.switchboard.gateway.user.UserStatus;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Admin-only member management. SecurityConfig restricts /admin/** to ADMIN. */
@RestController
@RequestMapping("/admin/members")
public class AdminMemberController {

	private final MemberService members;

	public AdminMemberController(MemberService members) {
		this.members = members;
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	public Mono<MemberView> invite(@Valid @RequestBody InviteRequest r) {
		return members.invite(r.email(), r.password(), r.limits()).map(MemberView::of);
	}

	@GetMapping
	public Flux<MemberView> list() {
		return members.list().map(MemberView::of);
	}

	@GetMapping("/{id}")
	public Mono<MemberView> get(@PathVariable UUID id) {
		return members.get(id).map(MemberView::of);
	}

	@PatchMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<MemberView> update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest r) {
		return members.update(id, r.status(), r.limits()).map(MemberView::of);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public Mono<Void> remove(@PathVariable UUID id) {
		return members.remove(id);
	}

	// ---------- request / response shapes ----------

	public record InviteRequest(
			@NotBlank @Email @Size(max = 320) String email,
			@NotBlank @Size(max = 200) String password,
			@JsonProperty("daily_budget_usd") BigDecimal dailyBudgetUsd,
			@JsonProperty("monthly_budget_usd") BigDecimal monthlyBudgetUsd,
			@JsonProperty("rpm_limit") Integer rpmLimit,
			@JsonProperty("allowed_models") List<String> allowedModels) {

		LimitsUpdate limits() {
			return new LimitsUpdate(dailyBudgetUsd, monthlyBudgetUsd, rpmLimit, allowedModels);
		}

		@Override
		public String toString() {
			return "InviteRequest[email=" + email + "]"; // never log the password
		}
	}

	/** Every field is optional; only the ones sent are changed. */
	public record UpdateRequest(
			UserStatus status,
			@JsonProperty("daily_budget_usd") BigDecimal dailyBudgetUsd,
			@JsonProperty("monthly_budget_usd") BigDecimal monthlyBudgetUsd,
			@JsonProperty("rpm_limit") Integer rpmLimit,
			@JsonProperty("allowed_models") List<String> allowedModels) {

		LimitsUpdate limits() {
			return new LimitsUpdate(dailyBudgetUsd, monthlyBudgetUsd, rpmLimit, allowedModels);
		}
	}

	public record MemberView(
			UUID id,
			String email,
			String status,
			@JsonProperty("created_at") Instant createdAt,
			LimitsView limits) {

		static MemberView of(Member m) {
			var l = m.limits();
			return new MemberView(m.user().id(), m.user().email(), m.user().status().name(), m.user().createdAt(),
					new LimitsView(l.dailyBudgetUsd(), l.monthlyBudgetUsd(), l.rpmLimit(), l.allowedModels()));
		}
	}

	public record LimitsView(
			@JsonProperty("daily_budget_usd") BigDecimal dailyBudgetUsd,
			@JsonProperty("monthly_budget_usd") BigDecimal monthlyBudgetUsd,
			@JsonProperty("rpm_limit") int rpmLimit,
			@JsonProperty("allowed_models") List<String> allowedModels) {
	}
}
