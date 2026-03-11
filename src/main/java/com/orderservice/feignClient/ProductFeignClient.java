package com.orderservice.feignClient;

import com.orderservice.commondtos.Product;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name = "PRODUCT-SERVICE")
public interface ProductFeignClient {
	@GetMapping("/api/products/{productId}")
	Product getProductById(@RequestHeader("X-USER-ID")  @PathVariable("productId") Long productId);
}