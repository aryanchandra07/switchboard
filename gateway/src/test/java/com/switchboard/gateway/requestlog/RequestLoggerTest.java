package com.switchboard.gateway.requestlog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(OutputCaptureExtension.class)
class RequestLoggerTest {

	private final RequestLogRepository repository = mock(RequestLogRepository.class);
	private final RequestLogger logger = new RequestLogger(repository);

	private static RequestLog entry() {
		return new RequestLog(null, null, null, "ollama", "llama3.2", null, 1, 2, BigDecimal.ZERO, 5, false,
				RequestLog.Status.SUCCESS, null, null, Instant.now());
	}

	@Test
	void record_savesEntry() {
		when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
		RequestLog e = entry();

		logger.record(e);

		verify(repository, timeout(2000)).save(e);
	}

	@Test
	void save_databaseError_isSwallowedAndReported(CapturedOutput output) {
		when(repository.save(any())).thenReturn(Mono.error(new RuntimeException("connection refused")));

		StepVerifier.create(logger.save(entry())).verifyComplete(); // completes normally, no error
		assertThat(output.getAll()).contains("Could not save request log").contains("connection refused");
	}

	@Test
	void record_repositoryThrowsImmediately_doesNotPropagate() {
		when(repository.save(any())).thenThrow(new IllegalStateException("pool exhausted"));
		assertThatCode(() -> logger.record(entry())).doesNotThrowAnyException();
	}

	@Test
	void record_returnsWithoutWaitingForSlowDatabase() {
		// A save that never completes must not block the caller.
		when(repository.save(any())).thenReturn(Mono.never());
		long start = System.nanoTime();
		logger.record(entry());
		assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(500);
	}
}
