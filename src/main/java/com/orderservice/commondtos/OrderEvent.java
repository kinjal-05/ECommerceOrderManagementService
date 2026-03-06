package com.orderservice.commondtos;

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
public class OrderEvent {
	private Long orderId;
	private Long userId;
	private Double totalAmount;
	private String status;
	private String shippingAddress;
	private String reason;

}
