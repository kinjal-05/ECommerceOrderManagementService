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
public class StockCheckResponse {
	private Boolean available;
	private String message;
	private List<StockItemStatus> items;

	@Data
	@Getter
	@Setter
	@NoArgsConstructor
	@AllArgsConstructor
	@Builder(toBuilder = true)
	public static class StockItemStatus {
		private Long productId;
		private Integer requested;
		private Integer available;
		private Boolean sufficient;

	}

}

