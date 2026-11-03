/*
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(org.jetbrains.exposed.v1.core.ExperimentalDatabaseMigrationApi::class)

package com.mozgobolt.core.database

import com.mozgobolt.core.data.idempotency.IdempotencyKeysTable
import com.mozgobolt.feature.company.data.database.CompaniesTable
import com.mozgobolt.feature.company.data.database.CompanyMembershipsTable
import com.mozgobolt.feature.companyFavorite.data.database.CompanyFavoritesTable
import com.mozgobolt.feature.deviceInstallation.data.database.DeviceInstallationsTable
import com.mozgobolt.feature.proximityNotification.data.database.ProximityNotificationsTable
import com.mozgobolt.feature.savedLocation.data.database.UserSavedLocationsTable
import com.mozgobolt.feature.sync.data.database.SyncEventsTable
import com.mozgobolt.feature.user.data.database.EmailVerificationTokensTable
import com.mozgobolt.feature.user.data.database.PasswordResetTokensTable
import com.mozgobolt.feature.user.data.database.RefreshTokensTable
import com.mozgobolt.feature.user.data.database.UserEmailOutboxTable
import com.mozgobolt.feature.user.data.database.UsersTable
import com.mozgobolt.feature.vehicle.data.database.VehiclesTable
import com.mozgobolt.feature.vehicleAssignment.data.database.VehicleAssignmentsTable
import com.mozgobolt.feature.vehiclePing.data.database.VehiclePingsTable
import com.mozgobolt.feature.vehicleTracking.data.database.VehicleLocationsTable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.sql.DataSource

private const val JDBC_URL_ENV = "EXPOSED_MIGRATION_PREVIEW_JDBC_URL"
private const val USER_ENV = "EXPOSED_MIGRATION_PREVIEW_USER"
private const val PASSWORD_ENV = "EXPOSED_MIGRATION_PREVIEW_PASSWORD"
private const val OUTPUT_DIRECTORY = "build/exposed-migration-preview"
private const val INITIAL_SCHEMA_ARGUMENT = "--initial-schema"
private const val INITIAL_SCHEMA_NAME = "V1__init_schema"
private const val CUSTOM_INITIAL_SCHEMA_RESOURCE = "db/schema/v1_custom.sql"
private val SAFE_LOCAL_DATABASE_URL =
    Regex("""^jdbc:postgresql://(?:localhost|127\.0\.0\.1|\[::1\])(?::\d+)?/([A-Za-z0-9_]+)(?:\?.*)?$""")

private val exposedTables: Array<Table> =
    arrayOf(
        UsersTable,
        RefreshTokensTable,
        EmailVerificationTokensTable,
        PasswordResetTokensTable,
        UserEmailOutboxTable,
        IdempotencyKeysTable,
        CompaniesTable,
        CompanyMembershipsTable,
        VehiclesTable,
        VehicleAssignmentsTable,
        VehicleLocationsTable,
        CompanyFavoritesTable,
        UserSavedLocationsTable,
        VehiclePingsTable,
        DeviceInstallationsTable,
        ProximityNotificationsTable,
        SyncEventsTable,
    )

fun main(args: Array<String>) {
    val generateInitialSchema = args.getOrNull(1) == INITIAL_SCHEMA_ARGUMENT
    require(args.size == if (generateInitialSchema) 2 else 1) {
        "Pass the new Flyway SQL script name; use $INITIAL_SCHEMA_ARGUMENT for initial V1 generation."
    }
    val scriptName = args.first()
    require(scriptName.matches(Regex("^[A-Za-z0-9_]+$"))) {
        "Script name may contain only ASCII letters, digits, and underscores."
    }
    require(!generateInitialSchema || scriptName == INITIAL_SCHEMA_NAME) {
        "Initial schema generation must target $INITIAL_SCHEMA_NAME."
    }

    val jdbcUrl = requiredEnvironmentVariable(JDBC_URL_ENV)
    val databaseName =
        SAFE_LOCAL_DATABASE_URL.matchEntire(jdbcUrl)?.groupValues?.get(1)
            ?: error("Use a local PostgreSQL URL for a disposable *_migration_preview database.")
    require(databaseName.endsWith("_migration_preview")) {
        "Refusing to inspect database '$databaseName'; its name must end with '_migration_preview'."
    }

    val outputDirectory = Path.of(OUTPUT_DIRECTORY).toAbsolutePath()
    Files.createDirectories(outputDirectory)
    val outputFile = outputDirectory.resolve("$scriptName.sql")
    check(!Files.exists(outputFile)) {
        "Refusing to overwrite $outputFile; choose a new script name or inspect/remove that generated preview."
    }

    val dataSource =
        DatabaseFactory.createHikariDataSource(
            dbUrl = jdbcUrl,
            dbUser = requiredEnvironmentVariable(USER_ENV),
            dbPassword = requiredEnvironmentVariable(PASSWORD_ENV),
            poolSize = 2,
        )
    try {
        requirePublicSchemaIsEmpty(dataSource)
        if (!generateInitialSchema) DatabaseFactory.runFlywayMigration(dataSource)

        val database = DatabaseFactory.createDatabase(dataSource)
        try {
            transaction(db = database) {
                maxAttempts = 1
                db.dialectMetadata.resetCaches()
                MigrationUtils.generateMigrationScript(
                    *exposedTables,
                    scriptDirectory = outputDirectory.toString(),
                    scriptName = scriptName,
                    withLogs = false,
                )
            }
        } finally {
            TransactionManager.closeAndUnregister(database)
        }
    } finally {
        (dataSource as AutoCloseable).close()
    }

    if (generateInitialSchema) appendCustomInitialSchemaSql(outputFile)

    if (generateInitialSchema) {
        println("Generated full initial V1 schema candidate: $outputFile")
    } else {
        println("Generated migration preview: $outputFile")
    }
    println("Review the generated SQL manually. This helper never applies it.")
}

private fun appendCustomInitialSchemaSql(outputFile: Path) {
    val customSql =
        checkNotNull(Thread.currentThread().contextClassLoader.getResourceAsStream(CUSTOM_INITIAL_SCHEMA_RESOURCE)) {
            "Missing custom initial schema SQL resource: $CUSTOM_INITIAL_SCHEMA_RESOURCE"
        }.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    val generatedSql = Files.readString(outputFile, StandardCharsets.UTF_8).trimEnd()
    Files.writeString(
        outputFile,
        buildString {
            appendLine(generatedSql)
            appendLine()
            appendLine(customSql.trim())
        },
        StandardCharsets.UTF_8,
    )
}

private fun requiredEnvironmentVariable(name: String): String =
    checkNotNull(System.getenv(name)) { "Set $name for this local disposable-database preview." }

private fun requirePublicSchemaIsEmpty(dataSource: DataSource) {
    dataSource.connection.use { connection ->
        val tables =
            connection.metaData
                .getTables(connection.catalog, "public", "%", arrayOf("TABLE"))
                .use { results ->
                    buildList {
                        while (results.next()) add(results.getString("TABLE_NAME"))
                    }
                }
        check(tables.isEmpty()) {
            "The preview database must have an empty public schema before generation; found ${tables.joinToString()}."
        }
    }
}
