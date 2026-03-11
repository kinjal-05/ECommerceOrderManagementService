package com.orderservice.controllers;

import com.orderservice.dtos.OrderRequest;
import com.orderservice.dtos.OrderResponse;
import com.orderservice.services.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * All route paths resolved from api-paths.yml at startup.
 *
 * api.order.base          → /api/orders
 * api.order.create        → /create
 * api.order.get-by-id     → /{id}
 * api.order.my-orders     → /myOrders
 * api.order.get-all       → /all
 * api.order.update-status → /updateStatus/{id}
 * api.order.cancel        → /cancel/{id}
 * api.order.confirm       → /confirm/{id}
 *
 * Bugs fixed from original:
 * - Manual constructor replaced with @RequiredArgsConstructor
 * - System.out.println removed from createOrder
 * - createOrder returns 201 CREATED instead of 200 OK
 * - cancelOrder uses @DeleteMapping → changed to @PostMapping
 *   (DELETE is for removing a resource, not changing its state to cancelled)
 * - confirmOrder uses @PutMapping → changed to @PostMapping
 *   (confirm is a state transition action, not a full resource replace)
 * - X-USER-ID header is Long directly — consistent with myOrders (no manual parsing needed)
 */
@RestController
@RequestMapping("${api.order.base}")
@RequiredArgsConstructor
public class OrderController {

	private final OrderService orderService;

	@PostMapping("${api.order.create}")
	public ResponseEntity<OrderResponse> createOrder(
			@RequestHeader("X-USER-ID") Long userId,
			@RequestBody OrderRequest request) {

		return ResponseEntity.status(HttpStatus.CREATED)
				.body(orderService.createOrder(userId, request));
	}

	@GetMapping("${api.order.get-by-id}")
	public ResponseEntity<OrderResponse> getById(@PathVariable Long id) {
		return ResponseEntity.ok(orderService.getOrderById(id));
	}

	@GetMapping("${api.order.my-orders}")
	public ResponseEntity<List<OrderResponse>> getMyOrders(
			@RequestHeader("X-USER-ID") Long userId) {

		return ResponseEntity.ok(orderService.getOrdersByUserId(userId));
	}

	@GetMapping("${api.order.get-all}")
	public ResponseEntity<Page<OrderResponse>> getAllOrders(
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "10") int size) {

		return ResponseEntity.ok(orderService.getAllOrders(PageRequest.of(page, size)));
	}

	@PatchMapping("${api.order.update-status}")
	public ResponseEntity<OrderResponse> updateStatus(
			@PathVariable Long id,
			@RequestParam String status) {

		return ResponseEntity.ok(orderService.updateOrderStatus(id, status));
	}

	@PostMapping("${api.order.cancel}")
	public ResponseEntity<Void> cancelOrder(@PathVariable Long id) {
		orderService.cancelOrder(id);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("${api.order.confirm}")
	public ResponseEntity<Void> confirmOrder(@PathVariable Long id) {
		orderService.confirmOrder(id);
		return ResponseEntity.noContent().build();
	}
}