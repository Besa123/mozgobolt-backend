package com.shelflife.feature.storageLocation.di

import com.shelflife.feature.storageLocation.data.repository.StorageLocationRepositoryI
import com.shelflife.feature.storageLocation.domain.StorageLocationRepository
import com.shelflife.feature.storageLocation.domain.StorageLocationService
import com.shelflife.feature.storageLocation.service.StorageLocationServiceI
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.provide

fun Application.configureStorageLocationDependencyInjection() {
    dependencies {
        provide<StorageLocationRepository> { StorageLocationRepositoryI() }
        provide<StorageLocationService>(::StorageLocationServiceI)
    }
}
