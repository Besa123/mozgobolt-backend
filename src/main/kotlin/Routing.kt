package com.shelflife

import com.shelflife.core.data.idempotency.IdempotencyStore
import com.shelflife.core.routing.apiV1
import com.shelflife.feature.health.routing.infrastructureRoutes
import com.shelflife.feature.pantryEntry.domain.PantryEntryService
import com.shelflife.feature.pantryEntry.routing.pantryEntryRoutes
import com.shelflife.feature.product.domain.ProductService
import com.shelflife.feature.product.routing.productRoutes
import com.shelflife.feature.quantityUnit.domain.QuantityUnitService
import com.shelflife.feature.quantityUnit.routing.quantityUnitRoutes
import com.shelflife.feature.storageLocation.domain.StorageLocationService
import com.shelflife.feature.storageLocation.routing.storageLocationRoutes
import com.shelflife.feature.user.domain.UserService
import com.shelflife.feature.user.routing.authRoutes
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.routing.routing
import javax.sql.DataSource

fun Application.configureRouting() {
    val userService: UserService by dependencies
    val productService: ProductService by dependencies
    val storageLocationService: StorageLocationService by dependencies
    val quantityUnitService: QuantityUnitService by dependencies
    val pantryEntryService: PantryEntryService by dependencies
    val idempotencyStore: IdempotencyStore by dependencies
    val dataSource: DataSource by dependencies

    routing {
        infrastructureRoutes(dataSource)

        apiV1 {
            authRoutes(userService, idempotencyStore)
            productRoutes(productService, idempotencyStore)
            storageLocationRoutes(storageLocationService, idempotencyStore)
            quantityUnitRoutes(quantityUnitService)
            pantryEntryRoutes(pantryEntryService, idempotencyStore)
        }
    }
}
