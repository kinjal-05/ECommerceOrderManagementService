package com.orderservice.feignClient;

import com.orderservice.commondtos.PaymentRequest;
import com.orderservice.commondtos.PaymentResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name = "PAYMENT-SERVICE")
public interface PaymentFeignClient {

	@PostMapping("/api/payments/create")
	PaymentResponse createPayment(
			@RequestHeader("X-USER-LONG-ID") String userId,
			@RequestBody PaymentRequest request
	);

	@PostMapping("/api/payments/cancelByOrder/{orderId}")
	PaymentResponse cancelPaymentByOrder(
			@PathVariable("orderId") Long orderId
	);
}
