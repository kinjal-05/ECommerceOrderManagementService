package com.orderservice.dtos;

import java.time.LocalDateTime;
import java.util.List;
import com.orderservice.enums.OrderStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Data
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class OrderResponse {
	private Long id;
	private Long userId;
	private OrderStatus status;
	private Double totalAmount;
	private String shippingAddress;
	private List<OrderItemResponse> items;
	private LocalDateTime createdAt;
	private LocalDateTime updatedAt;

}
