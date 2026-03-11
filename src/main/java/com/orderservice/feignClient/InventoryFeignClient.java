package com.orderservice.feignClient;

import com.orderservice.commondtos.StockCheckRequest;
import com.orderservice.commondtos.StockCheckResponse;
import com.orderservice.commondtos.StockRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "INVENTORY-SERVICE")
public interface InventoryFeignClient {

	@PostMapping("/api/inventory/check")
	StockCheckResponse checkStock(
			@RequestBody StockCheckRequest request);

	@PostMapping("/api/inventory/reserve")
	String reserveStock(@RequestBody StockRequest request);

	@PostMapping("/api/inventory/restore")
	String restoreStock(@RequestBody StockRequest request);

	@PostMapping("/api/inventory/reduce")
	String reduceStock(@RequestBody StockRequest request);
}
