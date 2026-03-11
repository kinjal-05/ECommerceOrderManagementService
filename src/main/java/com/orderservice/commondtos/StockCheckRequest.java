package com.orderservice.commondtos;

import java.util.List;
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
public class StockCheckRequest {
	private List<StockItem> items;

	@Data
	@Getter
	@Setter
	@NoArgsConstructor
	@AllArgsConstructor
	@Builder(toBuilder = true)
	public static class StockItem {
		private Long productId;
		private Integer quantity;

	}

}

