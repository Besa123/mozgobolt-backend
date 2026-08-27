package com.shelflife.feature.pantryEntry.di

import com.shelflife.feature.pantryEntry.data.repository.PantryEntryRepositoryI
import com.shelflife.feature.pantryEntry.domain.PantryEntryRepository
import com.shelflife.feature.pantryEntry.domain.PantryEntryService
import com.shelflife.feature.pantryEntry.service.PantryEntryServiceI
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.provide

fun Application.configurePantryEntryDependencyInjection() {
    dependencies {
        provide<PantryEntryRepository> { PantryEntryRepositoryI() }
        provide<PantryEntryService>(::PantryEntryServiceI)
    }
}
