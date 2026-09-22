package com.mozgobolt.feature.savedLocation.di

import com.mozgobolt.feature.savedLocation.data.repository.SavedLocationRepositoryI
import com.mozgobolt.feature.savedLocation.domain.SavedLocationRepository
import com.mozgobolt.feature.savedLocation.domain.SavedLocationService
import com.mozgobolt.feature.savedLocation.service.SavedLocationServiceI
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.provide

fun Application.configureSavedLocationDependencyInjection() {
    dependencies {
        provide<SavedLocationRepository> { SavedLocationRepositoryI() }
        provide<SavedLocationService>(::SavedLocationServiceI)
    }
}
