package com.shelflife.feature.quantityUnit.routing

import com.shelflife.core.configureTestEnvironment
import com.shelflife.core.installTestModules
import com.shelflife.core.routing.apiV1
import com.shelflife.core.testAccessTokenFor
import com.shelflife.feature.product.domain.model.UnitCategory
import com.shelflife.feature.quantityUnit.domain.model.QuantityUnit
import com.shelflife.feature.quantityUnit.routing.dto.response.QuantityUnitResponseDto
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

private const val LIST_PATH = "/api/v1/quantity-units"

private fun Application.installQuantityUnitRoutesTestApp(quantityUnitService: FakeQuantityUnitService) {
    installTestModules()
    routing {
        apiV1 {
            quantityUnitRoutes(quantityUnitService)
        }
    }
}

class QuantityUnitRoutesTest {
    @Test
    fun `listing requires authentication`() =
        testApplication {
            configureTestEnvironment()
            application { installQuantityUnitRoutesTestApp(FakeQuantityUnitService()) }

            val response = client.get(LIST_PATH)

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `listing returns the service's units as json`() =
        testApplication {
            configureTestEnvironment()
            val service =
                FakeQuantityUnitService().apply {
                    listResult =
                        listOf(
                            QuantityUnit(
                                id = 1,
                                name = "kg",
                                category = UnitCategory.MASS,
                                multiplier = BigDecimal("1000"),
                            ),
                        )
                }
            application { installQuantityUnitRoutesTestApp(service) }

            val response = client.get(LIST_PATH) { bearerAuth(testAccessTokenFor(userId = 1)) }

            assertEquals(HttpStatusCode.OK, response.status)
            val results = Json.decodeFromString<List<QuantityUnitResponseDto>>(response.bodyAsText())
            val unit = results.single()
            assertEquals(1, unit.id)
            assertEquals("kg", unit.name)
            assertEquals(UnitCategory.MASS, unit.category)
            assertEquals(1000.0, unit.multiplier)
        }
}
