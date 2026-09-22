package com.mozgobolt.feature.companyFavorite.di

import com.mozgobolt.feature.companyFavorite.data.repository.CompanyFavoriteRepositoryI
import com.mozgobolt.feature.companyFavorite.domain.CompanyFavoriteRepository
import com.mozgobolt.feature.companyFavorite.domain.CompanyFavoriteService
import com.mozgobolt.feature.companyFavorite.service.CompanyFavoriteServiceI
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.provide

fun Application.configureCompanyFavoriteDependencyInjection() {
    dependencies {
        provide<CompanyFavoriteRepository> { CompanyFavoriteRepositoryI() }
        provide<CompanyFavoriteService>(::CompanyFavoriteServiceI)
    }
}
