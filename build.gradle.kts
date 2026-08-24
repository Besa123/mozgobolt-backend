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
    implementation(ktorLibs.server.statusPages)
    implementation(ktorLibs.server.callLogging)
    implementation(ktorLibs.server.callId)
    implementation(ktorLibs.server.cors)
    implementation(ktorLibs.server.defaultHeaders)
    implementation(ktorLibs.server.forwardedHeader)

    implementation(libs.logback.classic)
    implementation(libs.kotlin.logging)
    implementation(libs.logstash.logback.encoder)
    implementation(libs.janino)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.java.time)
    implementation(libs.exposed.migration.core)
    implementation(libs.exposed.migration.jdbc)
    implementation(libs.postgresql)
    implementation(libs.hikariCP)
    implementation(libs.dotenv)
    implementation(libs.password4j)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.resend)

    testImplementation(kotlin("test"))
    testImplementation(ktorLibs.server.testHost)
}
