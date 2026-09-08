package com.shelflife.feature.pantryEntryImage.di

import com.shelflife.core.database.TransactionalRunner
import com.shelflife.core.domain.media.ImageSanitizer
import com.shelflife.core.domain.media.ImageStorage
import com.shelflife.core.domain.security.VirusScanner
import com.shelflife.core.modules.AppConfig
import com.shelflife.feature.pantryEntry.domain.PantryEntryImageCleanup
import com.shelflife.feature.pantryEntry.domain.PantryEntryRepository
import com.shelflife.feature.pantryEntryImage.data.repository.PantryEntryImageRepositoryI
import com.shelflife.feature.pantryEntryImage.domain.PantryEntryImageRepository
import com.shelflife.feature.pantryEntryImage.domain.PantryEntryImageService
import com.shelflife.feature.pantryEntryImage.domain.model.PantryEntryImagePolicy
import com.shelflife.feature.pantryEntryImage.service.PantryEntryImageCleanupI
import com.shelflife.feature.pantryEntryImage.service.PantryEntryImageServiceI
import com.shelflife.feature.sync.domain.SyncService
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.provide
import io.ktor.server.plugins.di.resolve

fun Application.configurePantryEntryImageDependencyInjection() {
    dependencies {
        provide<PantryEntryImageRepository> { PantryEntryImageRepositoryI() }
        provide<PantryEntryImageCleanup>(::PantryEntryImageCleanupI)

        provide<PantryEntryImageService> {
            val appConfig = resolve<AppConfig>()
            PantryEntryImageServiceI(
                pantryEntryRepository = resolve<PantryEntryRepository>(),
                pantryEntryImageRepository = resolve<PantryEntryImageRepository>(),
                imageSanitizer = resolve<ImageSanitizer>(),
                virusScanner = resolve<VirusScanner>(),
                imageStorage = resolve<ImageStorage>(),
                syncService = resolve<SyncService>(),
                tx = resolve<TransactionalRunner>(),
                policy =
                    PantryEntryImagePolicy(
                        maxImagesPerEntry = appConfig.media.maxImagesPerEntry,
                        clamAvEnabled = appConfig.clamAv.enabled,
                    ),
            )
        }
    }
}
