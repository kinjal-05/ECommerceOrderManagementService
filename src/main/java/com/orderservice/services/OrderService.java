package com.orderservice.services;
import java.util.List;

import com.orderservice.dtos.OrderRequest;
import com.orderservice.dtos.OrderResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
public interface OrderService {
	OrderResponse createOrder(Long userId, OrderRequest request);

	OrderResponse getOrderById(Long id);

	List<OrderResponse> getOrdersByUserId(Long userId);

	Page<OrderResponse> getAllOrders(Pageable pageable);

	OrderResponse updateOrderStatus(Long id, String status);

	void cancelOrder(Long id);

	void confirmOrder(Long orderId);
}
