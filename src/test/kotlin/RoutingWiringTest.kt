package com.shelflife

import com.shelflife.core.configureTestEnvironment
import com.shelflife.core.data.idempotency.FakeIdempotencyStore
import com.shelflife.core.data.idempotency.IdempotencyStore
import com.shelflife.core.installTestModules
import com.shelflife.feature.pantryEntry.domain.PantryEntryService
import com.shelflife.feature.pantryEntry.routing.FakePantryEntryService
import com.shelflife.feature.pantryEntryImage.domain.PantryEntryImageService
import com.shelflife.feature.pantryEntryImage.routing.FakePantryEntryImageService
import com.shelflife.feature.product.domain.ProductService
import com.shelflife.feature.product.routing.FakeProductService
import com.shelflife.feature.quantityUnit.domain.QuantityUnitService
import com.shelflife.feature.quantityUnit.routing.FakeQuantityUnitService
import com.shelflife.feature.storageLocation.domain.StorageLocationService
import com.shelflife.feature.storageLocation.routing.FakeStorageLocationService
import com.shelflife.feature.sync.domain.SyncEventHub
import com.shelflife.feature.sync.domain.SyncService
import com.shelflife.feature.sync.domain.model.SyncHint
import com.shelflife.feature.sync.routing.FakeSyncService
import com.shelflife.feature.user.domain.UserService
import com.shelflife.feature.user.routing.FakeUserService
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.io.PrintWriter
import java.sql.Connection
import java.sql.SQLFeatureNotSupportedException
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertNotEquals

class RoutingWiringTest {
    @Test
    fun `every feature's routes are actually registered on the real routing tree`() =
        testApplication {
            configureTestEnvironment()
            application {
                installTestModules()
                dependencies {
                    provide<UserService> { FakeUserService() }
                    provide<ProductService> { FakeProductService() }
                    provide<StorageLocationService> { FakeStorageLocationService() }
                    provide<QuantityUnitService> { FakeQuantityUnitService() }
                    provide<PantryEntryService> { FakePantryEntryService() }
                    provide<PantryEntryImageService> { FakePantryEntryImageService() }
                    provide<SyncService> { FakeSyncService() }
                    provide<SyncEventHub> { FakeSyncEventHub() }
                    provide<IdempotencyStore> { FakeIdempotencyStore() }
                    provide<DataSource> { FakeDataSource() }
                }
                configureRouting()
            }

            val protectedGetPaths =
                listOf(
                    "/api/v1/products/mine",
                    "/api/v1/storage-locations",
                    "/api/v1/quantity-units",
                    "/api/v1/pantry-entries",
                    "/api/v1/pantry-entries/1/images",
                    "/api/v1/sync",
                )

            protectedGetPaths.forEach { path ->
                val response = client.get(path)
                assertNotEquals(HttpStatusCode.NotFound, response.status, "expected $path to be a registered route")
            }

            val protectedPostPaths =
                listOf(
                    "/api/v1/pantry-entries/1/images",
                )

            protectedPostPaths.forEach { path ->
                val response = client.post(path)
                assertNotEquals(HttpStatusCode.NotFound, response.status, "expected $path to be a registered route")
            }
        }
}

private class FakeSyncEventHub : SyncEventHub {
    override fun publish(
        userId: Int,
        eventId: Long,
        originDeviceId: String?,
    ) = Unit

    override fun subscribe(userId: Int): Flow<SyncHint> = emptyFlow()
}

private class FakeDataSource : DataSource {
    override fun getConnection(): Connection = throw UnsupportedOperationException()

    override fun getConnection(
        username: String?,
        password: String?,
    ): Connection = throw UnsupportedOperationException()

    override fun getLogWriter(): PrintWriter = throw UnsupportedOperationException()

    override fun setLogWriter(out: PrintWriter?) = throw UnsupportedOperationException()

    override fun setLoginTimeout(seconds: Int) = throw UnsupportedOperationException()

    override fun getLoginTimeout(): Int = throw UnsupportedOperationException()

    override fun getParentLogger(): Logger = throw SQLFeatureNotSupportedException()

    override fun <T : Any?> unwrap(iface: Class<T>?): T = throw UnsupportedOperationException()

    override fun isWrapperFor(iface: Class<*>?): Boolean = false
}
