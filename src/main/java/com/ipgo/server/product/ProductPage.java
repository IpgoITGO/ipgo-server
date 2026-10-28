package com.ipgo.server.product;

import java.util.List;

public record ProductPage(List<ProductItem> items, long total, int page, int size) {
}
