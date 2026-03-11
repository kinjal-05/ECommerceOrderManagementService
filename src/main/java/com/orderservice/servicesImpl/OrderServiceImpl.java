package com.orderservice.servicesImpl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import com.orderservice.commondtos.*;
import com.orderservice.dtos.OrderItemResponse;
import com.orderservice.dtos.OrderRequest;
import com.orderservice.dtos.OrderResponse;
import com.orderservice.enums.OrderStatus;
import com.orderservice.exceptions.ResourceNotFoundException;
import com.orderservice.feignClient.InventoryFeignClient;
import com.orderservice.feignClient.PaymentFeignClient;
import com.orderservice.models.Order;
import com.orderservice.models.OrderItem;
import com.orderservice.repositories.OrderItemRepository;
import com.orderservice.repositories.OrderRepository;
import com.orderservice.services.OrderService;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
@Transactional
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

	private final OrderRepository orderRepository;
	private final OrderItemRepository orderItemRepository;
	private final PaymentFeignClient paymentFeignClient;
	private final InventoryFeignClient inventoryFeignClient;
	private final StreamBridge streamBridge;

	@Override
	public OrderResponse createOrder(Long userId, OrderRequest request) {
		if (request.getItems() == null || request.getItems().isEmpty()) {
			throw new RuntimeException("Order must have at least one item");
		}
		StockCheckRequest stockCheckRequest = new StockCheckRequest();
		List<StockCheckRequest.StockItem> stockItems = request.getItems().stream().map(item -> {
			StockCheckRequest.StockItem si = new StockCheckRequest.StockItem();
			si.setProductId(item.getProductId());
			si.setQuantity(item.getQuantity());
			return si;
		}).collect(Collectors.toList());
		stockCheckRequest.setItems(stockItems);
		StockCheckResponse stockResponse = inventoryFeignClient.checkStock(stockCheckRequest);
		if (!stockResponse.getAvailable()) {
			throw new RuntimeException("Insufficient stock: " + stockResponse.getMessage());
		}
		StockRequest stockRequest = new StockRequest();
		List<StockRequest.StockItem> reserveItems = request.getItems().stream().map(item -> {
			StockRequest.StockItem si = new StockRequest.StockItem();
			si.setProductId(item.getProductId());
			si.setQuantity(item.getQuantity());
			return si;
		}).collect(Collectors.toList());
		stockRequest.setItems(reserveItems);
		inventoryFeignClient.reserveStock(stockRequest);
		Order order = orderRepository.save(
				Order.builder().userId(userId).shippingAddress(request.getShippingAddress()).status(OrderStatus.PENDING)
						.totalAmount(0.0).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
		List<OrderItem> items = request.getItems().stream()
				.map(itemReq -> OrderItem.builder().productId(itemReq.getProductId()).quantity(itemReq.getQuantity())
						.price(itemReq.getPrice()).order(order)
						.build())
				.collect(Collectors.toList());

		double total = items.stream().mapToDouble(i -> i.getPrice() * i.getQuantity()).sum();
		orderItemRepository.saveAll(items);
		Order savedOrder = orderRepository.save(order.toBuilder().orderItems(items).totalAmount(total).build());
		return mapToResponse(savedOrder);
	}

	@Override
	public void cancelOrder(Long id) {
		Order order = orderRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Order not found: " + id));
		order.setStatus(OrderStatus.CANCELLED);
		order.setUpdatedAt(LocalDateTime.now());
		Order saved = orderRepository.save(order);
		try {
			StockRequest restoreRequest = new StockRequest();
			restoreRequest.setOrderId(saved.getId());
			List<StockRequest.StockItem> items = saved.getOrderItems().stream().map(item -> {
				StockRequest.StockItem si = new StockRequest.StockItem();
				si.setProductId(item.getProductId());
				si.setQuantity(item.getQuantity());
				return si;
			}).collect(Collectors.toList());
			restoreRequest.setItems(items);
			inventoryFeignClient.restoreStock(restoreRequest);
		} catch (Exception e) {
			System.out.println("❌ Stock restore failed: " + e.getMessage());
		}
		try {
			paymentFeignClient.cancelPaymentByOrder(saved.getId());
		} catch (Exception e) {
			System.out.println("❌ Could not cancel payment: " + e.getMessage());
		}
		publishOrderEvent(saved, "Order cancelled by user");
	}

	private void publishOrderEvent(Order order, String reason) {
		try {
			OrderEvent event = OrderEvent.builder().orderId(order.getId()).userId(order.getUserId())
					.totalAmount(order.getTotalAmount()).status(order.getStatus().name())
					.shippingAddress(order.getShippingAddress()).reason(reason).build();
			streamBridge.send("orderNotification-out-0", event);
		} catch (Exception e) {
			System.out.println("❌ Failed to publish order event: " + e.getMessage());
		}
	}

	@Override
	public OrderResponse getOrderById(Long id) {
		Order order = orderRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Order not found: " + id));
		return mapToResponse(order);
	}

	@Override
	public List<OrderResponse> getOrdersByUserId(Long userId) {
		return orderRepository.findByUserId(userId).stream().map(this::mapToResponse).collect(Collectors.toList());
	}

	@Override
	public Page<OrderResponse> getAllOrders(Pageable pageable) {
		return orderRepository.findAll(pageable).map(this::mapToResponse);
	}

	@Override
	public OrderResponse updateOrderStatus(Long id, String status) {
		Order order = orderRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Order not found: " + id));
		order.setStatus(OrderStatus.valueOf(status.toUpperCase()));
		order.setUpdatedAt(LocalDateTime.now());
		Order saved = orderRepository.save(order);
		String reason;
		switch (status.toUpperCase()) {
			case "SHIPPED":
				reason = "Your order has been shipped!";
				break;
			case "DELIVERED":
				reason = "Your order has been delivered!";
				break;
			case "CANCELLED":
				reason = "Your order has been cancelled.";
				break;
			default:
				reason = "Order status updated to " + status;
		}
		publishOrderEvent(saved, reason);
		return mapToResponse(saved);
	}

	private OrderResponse mapToResponse(Order order) {
		OrderResponse response = OrderResponse.builder().id(order.getId()).userId(order.getUserId())
				.status(order.getStatus()).totalAmount(order.getTotalAmount())
				.shippingAddress(order.getShippingAddress()).createdAt(order.getCreatedAt())
				.updatedAt(order.getUpdatedAt()).build();

		if (order.getOrderItems() != null) {
			List<OrderItemResponse> itemResponses = order.getOrderItems().stream().map(item -> {
				return OrderItemResponse.builder().id(item.getId()).productId(item.getProductId())
						.quantity(item.getQuantity()).price(item.getPrice()).build();
			}).collect(Collectors.toList());
			response.setItems(itemResponses);
		}

		return response;
	}

	@CircuitBreaker(name = "paymentService", fallbackMethod = "paymentFallback")
	public PaymentResponse callPaymentService(Long userId, PaymentRequest paymentRequest) {
		return paymentFeignClient.createPayment(String.valueOf(userId), paymentRequest);
	}

	public PaymentResponse paymentFallback(Long userId, PaymentRequest paymentRequest, Throwable ex) {
		PaymentResponse response = new PaymentResponse();
		response.setStatus("FAILED");
		return response;
	}

	@Override
	public void confirmOrder(Long orderId) {
		Order order = orderRepository.findById(orderId)
				.orElseThrow(() -> new RuntimeException("Order not found: " + orderId));
		order.setStatus(OrderStatus.CONFIRMED);
		order.setUpdatedAt(LocalDateTime.now());
		StockRequest stockRequest = new StockRequest();
		List<StockRequest.StockItem> stockItems = order.getOrderItems().stream().map(item -> {
			StockRequest.StockItem si = new StockRequest.StockItem();
			si.setProductId(item.getProductId());
			si.setQuantity(item.getQuantity());
			return si;
		}).collect(Collectors.toList());
		stockRequest.setItems(stockItems);
		stockRequest.setOrderId(orderId);
		inventoryFeignClient.reduceStock(stockRequest);
		orderRepository.save(order);
		publishOrderEvent(order, "Order confirmed successfully");
	}

	private StockRequest buildStockRequest(Order order) {
		StockRequest stockRequest = new StockRequest();
		List<StockRequest.StockItem> stockItems = order.getOrderItems().stream().map(item -> {
			StockRequest.StockItem si = new StockRequest.StockItem();
			si.setProductId(item.getProductId());
			si.setQuantity(item.getQuantity());
			return si;
		}).collect(Collectors.toList());
		stockRequest.setItems(stockItems);
		return stockRequest;
	}
}