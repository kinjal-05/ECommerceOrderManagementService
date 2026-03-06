package com.orderservice.controllers;
import java.util.List;

import com.orderservice.dtos.OrderRequest;
import com.orderservice.dtos.OrderResponse;
import com.orderservice.services.OrderService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

	private final OrderService orderService;

	public OrderController(OrderService orderService) {
		this.orderService = orderService;
	}

	@PostMapping("/createOrder")
	public ResponseEntity<OrderResponse> createOrder(@RequestHeader("X-USER-ID") Long userId,
	                                                 @RequestBody OrderRequest request) {
		System.out.println("✅ Creating order for userId: " + userId);
		return ResponseEntity.ok(orderService.createOrder(userId, request));
	}

	@GetMapping("/getById/{id}")
	public ResponseEntity<OrderResponse> getById(@PathVariable Long id) {
		return ResponseEntity.ok(orderService.getOrderById(id));
	}

	@GetMapping("/myOrders")
	public ResponseEntity<List<OrderResponse>> getMyOrders(@RequestHeader("X-USER-ID") Long userId) {
		return ResponseEntity.ok(orderService.getOrdersByUserId(userId));
	}

	@GetMapping("/all")
	public ResponseEntity<Page<OrderResponse>> getAllOrders(@RequestParam(defaultValue = "0") int page,
	                                                        @RequestParam(defaultValue = "10") int size) {
		return ResponseEntity.ok(orderService.getAllOrders(PageRequest.of(page, size)));
	}

	@PatchMapping("/updateStatus/{id}")
	public ResponseEntity<OrderResponse> updateStatus(@PathVariable Long id, @RequestParam String status) {
		return ResponseEntity.ok(orderService.updateOrderStatus(id, status));
	}

	@DeleteMapping("/cancel/{id}")
	public ResponseEntity<String> cancelOrder(@PathVariable Long id) {
		orderService.cancelOrder(id);
		return ResponseEntity.ok("Order cancelled successfully");
	}

	@PutMapping("/confirm/{id}")
	public ResponseEntity<String> confirmOrder(@PathVariable Long id) {
		orderService.confirmOrder(id);
		return ResponseEntity.ok("Order confirmed successfully");
	}
}

