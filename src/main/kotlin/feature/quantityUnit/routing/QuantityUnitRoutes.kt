package com.shelflife.feature.quantityUnit.routing

import com.shelflife.core.utility.functions.protectedApi
import com.shelflife.feature.quantityUnit.domain.QuantityUnitService
import com.shelflife.feature.quantityUnit.routing.dto.response.toResponseDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.quantityUnitRoutes(quantityUnitService: QuantityUnitService) {
    protectedApi {
        get("/quantity-units") {
            val units = quantityUnitService.listAll()
            call.respond(HttpStatusCode.OK, units.map { it.toResponseDto() })
        }
    }
}
