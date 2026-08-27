package com.shelflife.feature.quantityUnit.di

import com.shelflife.feature.quantityUnit.data.repository.QuantityUnitRepositoryI
import com.shelflife.feature.quantityUnit.domain.QuantityUnitRepository
import com.shelflife.feature.quantityUnit.domain.QuantityUnitService
import com.shelflife.feature.quantityUnit.service.QuantityUnitServiceI
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.provide

fun Application.configureQuantityUnitDependencyInjection() {
    dependencies {
        provide<QuantityUnitRepository> { QuantityUnitRepositoryI() }
        provide<QuantityUnitService>(::QuantityUnitServiceI)
    }
}
