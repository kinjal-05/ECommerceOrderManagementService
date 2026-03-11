package servicesImpl;

import com.orderservice.commondtos.*;
import com.orderservice.dtos.*;
import com.orderservice.enums.OrderStatus;
import com.orderservice.exceptions.ResourceNotFoundException;
import com.orderservice.feignClient.InventoryFeignClient;
import com.orderservice.feignClient.PaymentFeignClient;
import com.orderservice.feignClient.ProductFeignClient;
import com.orderservice.models.Order;
import com.orderservice.models.OrderItem;
import com.orderservice.repositories.OrderItemRepository;
import com.orderservice.repositories.OrderRepository;
import com.orderservice.servicesImpl.OrderServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrderServiceImpl Tests")
class OrderServiceImplTest {

	@Mock private ProductFeignClient productFeignClient;
	@Mock private OrderRepository orderRepository;
	@Mock private OrderItemRepository orderItemRepository;
	@Mock private PaymentFeignClient paymentFeignClient;
	@Mock private InventoryFeignClient inventoryFeignClient;
	@Mock private StreamBridge streamBridge;

	@InjectMocks
	private OrderServiceImpl orderService;

	// ─────────────────────────────────────────────────────────────────────────
	// Helpers
	// ─────────────────────────────────────────────────────────────────────────

	/** Build a minimal Order with id, userId, status, totalAmount */
	private Order buildOrder(Long id, Long userId, OrderStatus status, Double total) {
		return Order.builder()
				.id(id)
				.userId(userId)
				.status(status)
				.totalAmount(total)
				.shippingAddress("123 Main St")
				.createdAt(LocalDateTime.now())
				.updatedAt(LocalDateTime.now())
				.build();
	}

	/** Build an Order that already has OrderItems attached */
	private Order buildOrderWithItems(Long id, Long userId, OrderStatus status) {
		OrderItem item = OrderItem.builder()
				.id(1L)
				.productId(10L)
				.quantity(2)
				.price(500.0)
				.build();

		Order order = buildOrder(id, userId, status, 1000.0);
		order.setOrderItems(List.of(item));
		item.setOrder(order);
		return order;
	}

	/** Build a single-item OrderRequest */
	private OrderRequest buildOrderRequest(Long productId, int qty) {
		OrderItemRequest item = new OrderItemRequest();
		item.setProductId(productId);
		item.setQuantity(qty);

		OrderRequest req = new OrderRequest();
		req.setShippingAddress("123 Main St");
		req.setItems(List.of(item));
		req.setPaymentMethod("UPI");
		return req;
	}

	/** Build a StockCheckResponse with given availability */
	private StockCheckResponse buildStockResponse(boolean available, String message) {
		StockCheckResponse resp = new StockCheckResponse();
		resp.setAvailable(available);          // Boolean wrapper → setAvailable()
		resp.setMessage(message);
		return resp;
	}

	/** Build a Product with a price (assumed fields: productId, price) */
	private Product buildProduct(Long productId, double price) {
		Product p = new Product();
		p.setId(productId);
		p.setPrice(price);
		return p;
	}

	// =========================================================================
	// createOrder
	// =========================================================================

	@Nested
	@DisplayName("createOrder()")
	class CreateOrder {

		@Test
		@DisplayName("Valid request → stock checked, reserved, order saved and returned")
		void validRequest_createsOrder() {
			OrderRequest request = buildOrderRequest(10L, 2);

			// Stock check returns available = true
			when(inventoryFeignClient.checkStock(any(StockCheckRequest.class)))
					.thenReturn(buildStockResponse(true, "All available"));

			// Product price lookup
			when(productFeignClient.getProductById(10L)).thenReturn(buildProduct(10L, 500.0));

			// First save (pending order with total=0)
			Order pendingOrder = buildOrder(1L, 5L, OrderStatus.PENDING, 0.0);
			pendingOrder.setOrderItems(null);

			// Second save (order with items and total)
			Order savedOrder = buildOrderWithItems(1L, 5L, OrderStatus.PENDING);
			savedOrder.setTotalAmount(1000.0);

			when(orderRepository.save(any(Order.class)))
					.thenReturn(pendingOrder)   // first save
					.thenReturn(savedOrder);     // second save (with total)

			when(orderItemRepository.saveAll(anyList())).thenReturn(List.of());

			OrderResponse response = orderService.createOrder(5L, request);

			assertThat(response).isNotNull();
			assertThat(response.getUserId()).isEqualTo(5L);
			assertThat(response.getStatus()).isEqualTo(OrderStatus.PENDING);

			verify(inventoryFeignClient).checkStock(any(StockCheckRequest.class));
			verify(inventoryFeignClient).reserveStock(any(StockRequest.class));
			verify(orderRepository, times(2)).save(any(Order.class));
			verify(orderItemRepository).saveAll(anyList());
		}

		@Test
		@DisplayName("Empty items list → RuntimeException")
		void emptyItems_throwsException() {
			OrderRequest request = new OrderRequest();
			request.setItems(List.of());

			assertThatThrownBy(() -> orderService.createOrder(1L, request))
					.isInstanceOf(RuntimeException.class)
					.hasMessageContaining("Order must have at least one item");

			verifyNoInteractions(inventoryFeignClient, orderRepository);
		}

		@Test
		@DisplayName("Null items → RuntimeException")
		void nullItems_throwsException() {
			OrderRequest request = new OrderRequest();
			request.setItems(null);

			assertThatThrownBy(() -> orderService.createOrder(1L, request))
					.isInstanceOf(RuntimeException.class)
					.hasMessageContaining("Order must have at least one item");
		}

		@Test
		@DisplayName("Insufficient stock → RuntimeException with message")
		void insufficientStock_throwsException() {
			OrderRequest request = buildOrderRequest(10L, 100);

			// Boolean wrapper → getAvailable() returns false
			when(inventoryFeignClient.checkStock(any(StockCheckRequest.class)))
					.thenReturn(buildStockResponse(false, "Out of stock"));

			assertThatThrownBy(() -> orderService.createOrder(1L, request))
					.isInstanceOf(RuntimeException.class)
					.hasMessageContaining("Insufficient stock");

			verify(inventoryFeignClient).checkStock(any(StockCheckRequest.class));
			verify(inventoryFeignClient, never()).reserveStock(any());
			verifyNoInteractions(orderRepository);
		}


		@Test
		@DisplayName("StockCheckRequest contains correct items mapped from OrderRequest")
		void stockCheckRequest_hasCorrectItems() {
			OrderRequest request = buildOrderRequest(10L, 3);

			when(inventoryFeignClient.checkStock(any(StockCheckRequest.class)))
					.thenReturn(buildStockResponse(true, "OK"));
			when(productFeignClient.getProductById(10L)).thenReturn(buildProduct(10L, 200.0));

			Order pending = buildOrder(1L, 1L, OrderStatus.PENDING, 0.0);
			Order saved   = buildOrderWithItems(1L, 1L, OrderStatus.PENDING);
			when(orderRepository.save(any(Order.class))).thenReturn(pending).thenReturn(saved);
			when(orderItemRepository.saveAll(anyList())).thenReturn(List.of());

			orderService.createOrder(1L, request);

			ArgumentCaptor<StockCheckRequest> captor = ArgumentCaptor.forClass(StockCheckRequest.class);
			verify(inventoryFeignClient).checkStock(captor.capture());

			StockCheckRequest captured = captor.getValue();
			assertThat(captured.getItems()).hasSize(1);
			assertThat(captured.getItems().get(0).getProductId()).isEqualTo(10L);
			assertThat(captured.getItems().get(0).getQuantity()).isEqualTo(3);
		}

		@Test
		@DisplayName("Total amount is correctly calculated from price × quantity")
		void totalAmount_calculatedCorrectly() {
			OrderRequest request = buildOrderRequest(10L, 2);

			when(inventoryFeignClient.checkStock(any(StockCheckRequest.class)))
					.thenReturn(buildStockResponse(true, "OK"));
			when(productFeignClient.getProductById(10L)).thenReturn(buildProduct(10L, 750.0));

			Order pending = buildOrder(1L, 1L, OrderStatus.PENDING, 0.0);
			pending.setOrderItems(null);

			ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
			when(orderRepository.save(orderCaptor.capture()))
					.thenReturn(pending)
					.thenAnswer(inv -> inv.getArgument(0));
			when(orderItemRepository.saveAll(anyList())).thenReturn(List.of());

			orderService.createOrder(1L, request);

			// Second captured order should have total = 750.0 * 2 = 1500.0
			List<Order> capturedOrders = orderCaptor.getAllValues();
			double total = capturedOrders.get(capturedOrders.size() - 1).getTotalAmount();
			assertThat(total).isEqualTo(1500.0);
		}
	}

	// =========================================================================
	// createOrderFallback
	// =========================================================================

	@Nested
	@DisplayName("createOrderFallback()")
	class CreateOrderFallback {

		@Test
		@DisplayName("Returns CANCELLED OrderResponse with correct userId")
		void returnsCancelledResponse() {
			OrderRequest request = buildOrderRequest(1L, 1);
			Exception ex = new RuntimeException("Service down");

			OrderResponse response = orderService.createOrderFallback(7L, request, ex);

			assertThat(response.getId()).isNull();
			assertThat(response.getUserId()).isEqualTo(7L);
			assertThat(response.getStatus()).isEqualTo(OrderStatus.CANCELLED);
		}
	}

	// =========================================================================
	// cancelOrder
	// =========================================================================

	@Nested
	@DisplayName("cancelOrder()")
	class CancelOrder {

		@Test
		@DisplayName("Existing order → status set to CANCELLED, stock restored, payment cancelled")
		void cancelsOrderSuccessfully() {
			Order order = buildOrderWithItems(1L, 2L, OrderStatus.CONFIRMED);
			when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
			when(orderRepository.save(any(Order.class))).thenReturn(order);
			when(streamBridge.send(anyString(), any())).thenReturn(true);

			orderService.cancelOrder(1L);

			assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
			verify(orderRepository).save(order);
			verify(inventoryFeignClient).restoreStock(any(StockRequest.class));
			verify(paymentFeignClient).cancelPaymentByOrder(1L);
		}

		@Test
		@DisplayName("Order not found → ResourceNotFoundException")
		void orderNotFound_throwsException() {
			when(orderRepository.findById(99L)).thenReturn(Optional.empty());

			assertThatThrownBy(() -> orderService.cancelOrder(99L))
					.isInstanceOf(ResourceNotFoundException.class)
					.hasMessageContaining("Order not found: 99");
		}

		@Test
		@DisplayName("Stock restore failure → handled gracefully, order still cancelled")
		void stockRestoreFails_handledGracefully() {
			Order order = buildOrderWithItems(1L, 2L, OrderStatus.CONFIRMED);
			when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
			when(orderRepository.save(any())).thenReturn(order);
			when(streamBridge.send(anyString(), any())).thenReturn(true);

			doThrow(new RuntimeException("Inventory down"))
					.when(inventoryFeignClient).restoreStock(any(StockRequest.class));

			// Should NOT throw — exception is caught internally
			assertThatNoException().isThrownBy(() -> orderService.cancelOrder(1L));
			assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
		}

		@Test
		@DisplayName("Payment cancel failure → handled gracefully, order still cancelled")
		void paymentCancelFails_handledGracefully() {
			Order order = buildOrderWithItems(1L, 2L, OrderStatus.CONFIRMED);
			when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
			when(orderRepository.save(any())).thenReturn(order);
			when(streamBridge.send(anyString(), any())).thenReturn(true);

			doThrow(new RuntimeException("Payment service down"))
					.when(paymentFeignClient).cancelPaymentByOrder(any());

			assertThatNoException().isThrownBy(() -> orderService.cancelOrder(1L));
			assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
		}

		@Test
		@DisplayName("Publishes OrderEvent with CANCELLED status to orderNotification-out-0")
		void publishesOrderEvent() {
			Order order = buildOrderWithItems(1L, 2L, OrderStatus.CONFIRMED);
			when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
			when(orderRepository.save(any())).thenReturn(order);

			ArgumentCaptor<OrderEvent> eventCaptor = ArgumentCaptor.forClass(OrderEvent.class);
			when(streamBridge.send(eq("orderNotification-out-0"), eventCaptor.capture()))
					.thenReturn(true);

			orderService.cancelOrder(1L);

			OrderEvent event = eventCaptor.getValue();
			assertThat(event.getOrderId()).isEqualTo(1L);
			assertThat(event.getUserId()).isEqualTo(2L);
			assertThat(event.getStatus()).isEqualTo("CANCELLED");
			assertThat(event.getReason()).isEqualTo("Order cancelled by user");
		}
	}

	// =========================================================================
	// getOrderById
	// =========================================================================

	@Nested
	@DisplayName("getOrderById()")
	class GetOrderById {

		@Test
		@DisplayName("Existing order → returns mapped OrderResponse")
		void existingOrder_returnsResponse() {
			Order order = buildOrderWithItems(5L, 3L, OrderStatus.CONFIRMED);
			when(orderRepository.findById(5L)).thenReturn(Optional.of(order));

			OrderResponse response = orderService.getOrderById(5L);

			assertThat(response.getId()).isEqualTo(5L);
			assertThat(response.getUserId()).isEqualTo(3L);
			assertThat(response.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
			assertThat(response.getItems()).hasSize(1);
		}

		@Test
		@DisplayName("Non-existent order → ResourceNotFoundException")
		void notFound_throwsException() {
			when(orderRepository.findById(99L)).thenReturn(Optional.empty());

			assertThatThrownBy(() -> orderService.getOrderById(99L))
					.isInstanceOf(ResourceNotFoundException.class)
					.hasMessageContaining("Order not found: 99");
		}
	}

	// =========================================================================
	// getOrdersByUserId
	// =========================================================================

	@Nested
	@DisplayName("getOrdersByUserId()")
	class GetOrdersByUserId {

		@Test
		@DisplayName("Returns all orders for given userId")
		void returnsOrdersForUser() {
			Order o1 = buildOrderWithItems(1L, 4L, OrderStatus.PENDING);
			Order o2 = buildOrderWithItems(2L, 4L, OrderStatus.CONFIRMED);
			when(orderRepository.findByUserId(4L)).thenReturn(List.of(o1, o2));

			List<OrderResponse> responses = orderService.getOrdersByUserId(4L);

			assertThat(responses).hasSize(2);
			assertThat(responses.get(0).getUserId()).isEqualTo(4L);
			assertThat(responses.get(1).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
		}

		@Test
		@DisplayName("No orders for user → returns empty list")
		void noOrders_returnsEmptyList() {
			when(orderRepository.findByUserId(99L)).thenReturn(List.of());

			List<OrderResponse> responses = orderService.getOrdersByUserId(99L);

			assertThat(responses).isEmpty();
		}
	}

	// =========================================================================
	// getAllOrders
	// =========================================================================

	@Nested
	@DisplayName("getAllOrders()")
	class GetAllOrders {

		@Test
		@DisplayName("Returns paged OrderResponse list")
		void returnsMappedPage() {
			Order order = buildOrderWithItems(1L, 1L, OrderStatus.PENDING);
			Page<Order> page = new PageImpl<>(List.of(order));
			Pageable pageable = PageRequest.of(0, 10);

			when(orderRepository.findAll(pageable)).thenReturn(page);

			Page<OrderResponse> result = orderService.getAllOrders(pageable);

			assertThat(result.getContent()).hasSize(1);
			assertThat(result.getContent().get(0).getId()).isEqualTo(1L);
		}

		@Test
		@DisplayName("Empty repository → returns empty page")
		void emptyRepository_returnsEmptyPage() {
			Page<Order> emptyPage = new PageImpl<>(List.of());
			Pageable pageable = PageRequest.of(0, 10);

			when(orderRepository.findAll(pageable)).thenReturn(emptyPage);

			Page<OrderResponse> result = orderService.getAllOrders(pageable);

			assertThat(result.getContent()).isEmpty();
		}
	}

	// =========================================================================
	// updateOrderStatus
	// =========================================================================

	@Nested
	@DisplayName("updateOrderStatus()")
	class UpdateOrderStatus {

		@Test
		@DisplayName("SHIPPED → status updated and event published with correct reason")
		void shippedStatus_updatesAndPublishes() {
			Order order = buildOrderWithItems(1L, 1L, OrderStatus.CONFIRMED);
			when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
			when(orderRepository.save(any())).thenReturn(order);

			ArgumentCaptor<OrderEvent> eventCaptor = ArgumentCaptor.forClass(OrderEvent.class);
			when(streamBridge.send(eq("orderNotification-out-0"), eventCaptor.capture()))
					.thenReturn(true);

			OrderResponse response = orderService.updateOrderStatus(1L, "SHIPPED");

			assertThat(order.getStatus()).isEqualTo(OrderStatus.SHIPPED);
			assertThat(eventCaptor.getValue().getReason()).isEqualTo("Your order has been shipped!");
		}

		@Test
		@DisplayName("DELIVERED → status updated and event published with correct reason")
		void deliveredStatus_updatesAndPublishes() {
			Order order = buildOrderWithItems(1L, 1L, OrderStatus.SHIPPED);
			when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
			when(orderRepository.save(any())).thenReturn(order);

			ArgumentCaptor<OrderEvent> eventCaptor = ArgumentCaptor.forClass(OrderEvent.class);
			when(streamBridge.send(anyString(), eventCaptor.capture())).thenReturn(true);

			orderService.updateOrderStatus(1L, "DELIVERED");

			assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERED);
			assertThat(eventCaptor.getValue().getReason()).isEqualTo("Your order has been delivered!");
		}

		@Test
		@DisplayName("CANCELLED → status updated and event published with correct reason")
		void cancelledStatus_updatesAndPublishes() {
			Order order = buildOrderWithItems(1L, 1L, OrderStatus.CONFIRMED);
			when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
			when(orderRepository.save(any())).thenReturn(order);

			ArgumentCaptor<OrderEvent> eventCaptor = ArgumentCaptor.forClass(OrderEvent.class);
			when(streamBridge.send(anyString(), eventCaptor.capture())).thenReturn(true);

			orderService.updateOrderStatus(1L, "CANCELLED");

			assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
			assertThat(eventCaptor.getValue().getReason()).isEqualTo("Your order has been cancelled.");
		}

		@Test
		@DisplayName("Unknown status string → default reason in event")
		void unknownStatus_usesDefaultReason() {
			Order order = buildOrderWithItems(1L, 1L, OrderStatus.PENDING);
			when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
			when(orderRepository.save(any())).thenReturn(order);

			ArgumentCaptor<OrderEvent> eventCaptor = ArgumentCaptor.forClass(OrderEvent.class);
			when(streamBridge.send(anyString(), eventCaptor.capture())).thenReturn(true);

			orderService.updateOrderStatus(1L, "PROCESSING");

			assertThat(eventCaptor.getValue().getReason()).contains("PROCESSING");
		}

		@Test
		@DisplayName("Order not found → ResourceNotFoundException")
		void orderNotFound_throwsException() {
			when(orderRepository.findById(99L)).thenReturn(Optional.empty());

			assertThatThrownBy(() -> orderService.updateOrderStatus(99L, "SHIPPED"))
					.isInstanceOf(ResourceNotFoundException.class)
					.hasMessageContaining("Order not found: 99");
		}

		@Test
		@DisplayName("Status is case-insensitive (lowercase input)")
		void statusCaseInsensitive() {
			Order order = buildOrderWithItems(1L, 1L, OrderStatus.CONFIRMED);
			when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
			when(orderRepository.save(any())).thenReturn(order);
			when(streamBridge.send(anyString(), any())).thenReturn(true);

			OrderResponse response = orderService.updateOrderStatus(1L, "shipped");

			assertThat(order.getStatus()).isEqualTo(OrderStatus.SHIPPED);
		}
	}

	// =========================================================================
	// updateOrderStatusFallback
	// =========================================================================

	@Nested
	@DisplayName("updateOrderStatusFallback()")
	class UpdateOrderStatusFallback {

		@Test
		@DisplayName("Returns current order state without throwing")
		void returnsMappedOrder() {
			Order order = buildOrderWithItems(1L, 1L, OrderStatus.CONFIRMED);
			when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

			OrderResponse response = orderService.updateOrderStatusFallback(
					1L, "SHIPPED", new RuntimeException("CB triggered"));

			assertThat(response.getId()).isEqualTo(1L);
			assertThat(response.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
		}

		@Test
		@DisplayName("Order not found in fallback → ResourceNotFoundException")
		void notFound_throwsException() {
			when(orderRepository.findById(99L)).thenReturn(Optional.empty());

			assertThatThrownBy(() -> orderService.updateOrderStatusFallback(
					99L, "SHIPPED", new RuntimeException("CB")))
					.isInstanceOf(ResourceNotFoundException.class);
		}
	}

	// =========================================================================
	// confirmOrder
	// =========================================================================

	@Nested
	@DisplayName("confirmOrder()")
	class ConfirmOrder {

		@Test
		@DisplayName("Existing order → status CONFIRMED, stock reduced, event published")
		void confirmsOrderSuccessfully() {
			Order order = buildOrderWithItems(1L, 2L, OrderStatus.PENDING);
			when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
			when(orderRepository.save(any())).thenReturn(order);

			ArgumentCaptor<OrderEvent> eventCaptor = ArgumentCaptor.forClass(OrderEvent.class);
			when(streamBridge.send(eq("orderNotification-out-0"), eventCaptor.capture()))
					.thenReturn(true);

			orderService.confirmOrder(1L);

			assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
			verify(inventoryFeignClient).reduceStock(any(StockRequest.class));
			verify(orderRepository).save(order);

			OrderEvent event = eventCaptor.getValue();
			assertThat(event.getOrderId()).isEqualTo(1L);
			assertThat(event.getReason()).isEqualTo("Order confirmed successfully");
		}

		@Test
		@DisplayName("StockRequest contains correct orderId and items")
		void stockRequest_hasCorrectOrderIdAndItems() {
			Order order = buildOrderWithItems(5L, 3L, OrderStatus.PENDING);
			when(orderRepository.findById(5L)).thenReturn(Optional.of(order));
			when(orderRepository.save(any())).thenReturn(order);
			when(streamBridge.send(anyString(), any())).thenReturn(true);

			ArgumentCaptor<StockRequest> stockCaptor = ArgumentCaptor.forClass(StockRequest.class);

			orderService.confirmOrder(5L);

			verify(inventoryFeignClient).reduceStock(stockCaptor.capture());
			StockRequest captured = stockCaptor.getValue();

			assertThat(captured.getOrderId()).isEqualTo(5L);
			assertThat(captured.getItems()).hasSize(1);
			assertThat(captured.getItems().get(0).getProductId()).isEqualTo(10L);
			assertThat(captured.getItems().get(0).getQuantity()).isEqualTo(2);
		}

		@Test
		@DisplayName("Order not found → RuntimeException")
		void orderNotFound_throwsException() {
			when(orderRepository.findById(99L)).thenReturn(Optional.empty());

			assertThatThrownBy(() -> orderService.confirmOrder(99L))
					.isInstanceOf(RuntimeException.class)
					.hasMessageContaining("Order not found: 99");
		}
	}

	// =========================================================================
	// updateOrderAmount
	// =========================================================================

	@Nested
	@DisplayName("updateOrderAmount()")
	class UpdateOrderAmount {

		@Test
		@DisplayName("Updates totalAmount from UpdateAmountRequest.remainingAmount")
		void updatesTotalAmount() {
			Order order = buildOrder(1L, 1L, OrderStatus.CONFIRMED, 1000.0);
			order.setOrderItems(List.of());

			when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
			when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

			// UpdateAmountRequest has: remainingAmount (Double)
			UpdateAmountRequest request = new UpdateAmountRequest(750.0);

			OrderResponse response = orderService.updateOrderAmount(1L, request);

			assertThat(order.getTotalAmount()).isEqualTo(750.0);
			assertThat(response.getTotalAmount()).isEqualTo(750.0);
			verify(orderRepository).save(order);
		}

		@Test
		@DisplayName("Order not found → RuntimeException")
		void orderNotFound_throwsException() {
			when(orderRepository.findById(99L)).thenReturn(Optional.empty());

			assertThatThrownBy(() -> orderService.updateOrderAmount(99L, new UpdateAmountRequest(100.0)))
					.isInstanceOf(RuntimeException.class)
					.hasMessageContaining("Order not found: 99");
		}
	}

	// =========================================================================
	// callPaymentService / paymentFallback
	// =========================================================================

	@Nested
	@DisplayName("callPaymentService() / paymentFallback()")
	class PaymentService {

		@Test
		@DisplayName("Feign call succeeds → returns PaymentResponse")
		void paymentSuccess_returnsResponse() {
			PaymentRequest paymentRequest = new PaymentRequest();
			paymentRequest.setOrderId(1L);
			paymentRequest.setAmount(500.0);
			paymentRequest.setMethod("CARD");

			PaymentResponse paymentResponse = new PaymentResponse();
			paymentResponse.setStatus("SUCCESS");
			paymentResponse.setTransactionId("TXN001");

			when(paymentFeignClient.createPayment(eq("1"), any(PaymentRequest.class)))
					.thenReturn(paymentResponse);

			PaymentResponse result = orderService.callPaymentService(1L, paymentRequest);

			assertThat(result.getStatus()).isEqualTo("SUCCESS");
			assertThat(result.getTransactionId()).isEqualTo("TXN001");
		}

		@Test
		@DisplayName("paymentFallback → returns PaymentResponse with FAILED status")
		void paymentFallback_returnsFailedResponse() {
			PaymentRequest paymentRequest = new PaymentRequest();
			Throwable ex = new RuntimeException("Payment service down");

			PaymentResponse result = orderService.paymentFallback(1L, paymentRequest, ex);

			// PaymentResponse.status is String → getStatus()
			assertThat(result.getStatus()).isEqualTo("FAILED");
		}
	}
}