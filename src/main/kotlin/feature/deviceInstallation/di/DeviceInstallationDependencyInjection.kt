package com.mozgobolt.feature.deviceInstallation.di

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.domain.push.PushNotificationSender
import com.mozgobolt.feature.deviceInstallation.data.repository.DeviceInstallationRepositoryI
import com.mozgobolt.feature.deviceInstallation.domain.DeviceInstallationRepository
import com.mozgobolt.feature.deviceInstallation.domain.DeviceInstallationService
import com.mozgobolt.feature.deviceInstallation.service.DeviceInstallationServiceI
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.provide
import io.ktor.server.plugins.di.resolve

fun Application.configureDeviceInstallationDependencyInjection() {
    dependencies {
        provide<DeviceInstallationRepository> { DeviceInstallationRepositoryI() }
        provide<DeviceInstallationService> {
            DeviceInstallationServiceI(
                deviceInstallationRepository = resolve<DeviceInstallationRepository>(),
                tx = resolve<TransactionalRunner>(),
                pushNotificationSender = resolve<PushNotificationSender>(),
            )
        }
    }
}
