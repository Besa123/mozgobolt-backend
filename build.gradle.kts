plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(ktorLibs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
}

group = "com.besa.boardShare"
version = "1.0.0-SNAPSHOT"

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

kotlin {
    jvmToolchain(21)
}
dependencies {
    implementation(ktorLibs.server.core)
    implementation(ktorLibs.server.netty)
    implementation(ktorLibs.server.di)
    implementation(ktorLibs.server.resources)
    implementation(ktorLibs.server.autoHeadResponse)
    implementation(ktorLibs.server.requestValidation)
    implementation(ktorLibs.server.rateLimit)
    implementation(ktorLibs.server.contentNegotiation)
    implementation(ktorLibs.serialization.kotlinx.json)
    implementation(ktorLibs.server.auth)
    implementation(ktorLibs.server.auth.jwt)

    implementation(libs.logback.classic)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.dao)
    implementation(libs.postgresql)
    implementation(libs.hikariCP)
    implementation(libs.dotenv)
    implementation(libs.password4j)

    testImplementation(kotlin("test"))
    testImplementation(ktorLibs.server.testHost)
}
