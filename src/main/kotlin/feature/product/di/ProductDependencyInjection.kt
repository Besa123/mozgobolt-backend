package com.shelflife.feature.product.di

import com.shelflife.feature.product.data.repository.ProductRepositoryI
import com.shelflife.feature.product.domain.ProductRepository
import com.shelflife.feature.product.domain.ProductService
import com.shelflife.feature.product.service.ProductServiceI
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.provide

fun Application.configureProductDependencyInjection() {
    dependencies {
        provide<ProductRepository> { ProductRepositoryI() }
        provide<ProductService>(::ProductServiceI)
    }
}
