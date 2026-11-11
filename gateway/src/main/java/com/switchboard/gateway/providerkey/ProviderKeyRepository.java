package com.switchboard.gateway.providerkey;

import java.util.UUID;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ProviderKeyRepository extends ReactiveCrudRepository<ProviderKey, UUID> {

	Mono<ProviderKey> findByProvider(String provider);

	Flux<ProviderKey> findAllByOrderByProviderAsc();
}
