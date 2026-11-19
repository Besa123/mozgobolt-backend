/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.proximityNotification.di

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.feature.deviceInstallation.domain.DeviceInstallationService
import com.mozgobolt.feature.proximityNotification.data.repository.ProximityAlertCandidateRepositoryI
import com.mozgobolt.feature.proximityNotification.data.repository.ProximityNotificationRepositoryI
import com.mozgobolt.feature.proximityNotification.domain.ProximityAlertCandidateRepository
import com.mozgobolt.feature.proximityNotification.domain.ProximityAlertService
import com.mozgobolt.feature.proximityNotification.domain.ProximityNotificationRepository
import com.mozgobolt.feature.proximityNotification.service.ProximityAlertServiceI
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.provide
import io.ktor.server.plugins.di.resolve

fun Application.configureProximityNotificationDependencyInjection() {
    dependencies {
        provide<ProximityAlertCandidateRepository> { ProximityAlertCandidateRepositoryI() }
        provide<ProximityNotificationRepository> { ProximityNotificationRepositoryI() }
        provide<ProximityAlertService> {
            ProximityAlertServiceI(
                candidateRepository = resolve<ProximityAlertCandidateRepository>(),
                proximityNotificationRepository = resolve<ProximityNotificationRepository>(),
                deviceInstallationService = resolve<DeviceInstallationService>(),
                tx = resolve<TransactionalRunner>(),
            )
        }
    }
}
