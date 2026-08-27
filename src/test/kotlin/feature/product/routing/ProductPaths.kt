package com.shelflife.feature.product.routing

object ProductPaths {
    private const val BASE = "/api/v1/products"
    const val SEARCH = "$BASE/search"
    const val MINE = "$BASE/mine"
    const val CREATE = BASE

    fun rename(productId: Int) = "$BASE/$productId"
}
