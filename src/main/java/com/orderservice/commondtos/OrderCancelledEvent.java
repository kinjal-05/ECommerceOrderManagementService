package com.orderservice.commondtos;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OrderCancelledEvent {
	private Long orderId;
	private Long userId;
	private String reason;
	private List<OrderItemEvent> items; // ✅ contains productId + quantity
}