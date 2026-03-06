package com.orderservice.consumer;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.orderservice.commondtos.OrderEvent;
import com.orderservice.commondtos.StockRequest;
import com.orderservice.commondtos.UserDeletedEvent;
import com.orderservice.enums.OrderStatus;
import com.orderservice.exceptions.ResourceNotFoundException;
import com.orderservice.feignClient.InventoryFeignClient;
import com.orderservice.feignClient.PaymentFeignClient;
import com.orderservice.models.Order;
import com.orderservice.repositories.OrderRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderServiceConsumer {

	private final OrderRepository orderRepository;
	private final StreamBridge streamBridge;
	private final PaymentFeignClient paymentFeignClient;
	private final InventoryFeignClient inventoryFeignClient;

	@Bean
	@Transactional
	public Consumer<UserDeletedEvent> userDeleted() {
		return event -> {
			log.info("📩 Order Service received user-deleted for userId: {}", event.getUserId());

			List<Order> orders = orderRepository.findByUserId(event.getUserId());

			orders.forEach(order -> {
				log.info("Processing order: {}", order.getId());

				if (order.getStatus() == OrderStatus.PENDING || order.getStatus() == OrderStatus.PROCESSING) {

					Long id = order.getId();
					Order fetchedOrder = orderRepository.findById(id)
							.orElseThrow(() -> new ResourceNotFoundException("Order not found: " + id));

					fetchedOrder.setStatus(OrderStatus.CANCELLED);
					fetchedOrder.setUpdatedAt(LocalDateTime.now());
					Order saved = orderRepository.save(fetchedOrder);

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
						log.error("❌ Stock restore failed for orderId {}: {}", saved.getId(), e.getMessage());
					}

					try {
						paymentFeignClient.cancelPaymentByOrder(saved.getId());
					} catch (Exception e) {
						log.error("❌ Could not cancel payment for orderId {}: {}", saved.getId(), e.getMessage());
					}

					publishOrderEvent(saved, "Order cancelled by user");
				}
			});
		};
	}

	private void publishOrderEvent(Order order, String reason) {
		try {
			OrderEvent event = OrderEvent.builder().orderId(order.getId()).userId(order.getUserId())
					.totalAmount(order.getTotalAmount()).status(order.getStatus().name())
					.shippingAddress(order.getShippingAddress()).reason(reason).build();
			streamBridge.send("orderNotification-out-0", event);
		} catch (Exception e) {
			log.error("❌ Failed to publish order event for orderId {}: {}", order.getId(), e.getMessage());
		}
	}
}