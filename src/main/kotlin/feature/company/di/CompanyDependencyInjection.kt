package com.mozgobolt.feature.company.di

import com.mozgobolt.feature.company.data.repository.CompanyMembershipRepositoryI
import com.mozgobolt.feature.company.data.repository.CompanyRepositoryI
import com.mozgobolt.feature.company.domain.CompanyMembershipRepository
import com.mozgobolt.feature.company.domain.CompanyRepository
import com.mozgobolt.feature.company.domain.CompanyService
import com.mozgobolt.feature.company.service.CompanyServiceI
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.provide

fun Application.configureCompanyDependencyInjection() {
    dependencies {
        provide<CompanyRepository> { CompanyRepositoryI() }
        provide<CompanyMembershipRepository> { CompanyMembershipRepositoryI() }
        provide<CompanyService>(::CompanyServiceI)
    }
}
