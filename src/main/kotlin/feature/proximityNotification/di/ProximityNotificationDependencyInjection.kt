package com.mozgobolt.feature.proximityNotification.di

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.feature.companyFavorite.domain.CompanyFavoriteRepository
import com.mozgobolt.feature.deviceInstallation.domain.DeviceInstallationService
import com.mozgobolt.feature.proximityNotification.data.repository.ProximityNotificationRepositoryI
import com.mozgobolt.feature.proximityNotification.domain.ProximityAlertService
import com.mozgobolt.feature.proximityNotification.domain.ProximityNotificationRepository
import com.mozgobolt.feature.proximityNotification.service.ProximityAlertServiceI
import com.mozgobolt.feature.savedLocation.domain.SavedLocationRepository
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.provide
import io.ktor.server.plugins.di.resolve

fun Application.configureProximityNotificationDependencyInjection() {
    dependencies {
        provide<ProximityNotificationRepository> { ProximityNotificationRepositoryI() }
        provide<ProximityAlertService> {
            ProximityAlertServiceI(
                companyFavoriteRepository = resolve<CompanyFavoriteRepository>(),
                savedLocationRepository = resolve<SavedLocationRepository>(),
                proximityNotificationRepository = resolve<ProximityNotificationRepository>(),
                deviceInstallationService = resolve<DeviceInstallationService>(),
                tx = resolve<TransactionalRunner>(),
            )
        }
    }
}
