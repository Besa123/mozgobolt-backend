/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.loadtest

import com.mozgobolt.core.data.security.JwtTokenManager
import com.mozgobolt.core.data.security.PasswordServiceImpl
import com.mozgobolt.core.database.DatabaseFactory
import com.mozgobolt.core.database.ExposedTransactionalRunner
import com.mozgobolt.core.domain.security.SecureTokenGenerator
import com.mozgobolt.core.domain.security.TokenManager
import com.mozgobolt.feature.company.data.repository.CompanyMembershipRepositoryI
import com.mozgobolt.feature.company.data.repository.CompanyRepositoryI
import com.mozgobolt.feature.company.domain.model.CompanyConstraints
import com.mozgobolt.feature.company.domain.model.CompanyRole
import com.mozgobolt.feature.companyFavorite.data.repository.CompanyFavoriteRepositoryI
import com.mozgobolt.feature.savedLocation.data.repository.SavedLocationRepositoryI
import com.mozgobolt.feature.user.data.repository.UserRepositoryI
import com.mozgobolt.feature.user.domain.model.UserRole
import com.mozgobolt.feature.vehicle.data.repository.VehicleRepositoryI
import com.mozgobolt.feature.vehicleAssignment.data.repository.VehicleAssignmentRepositoryI
import com.mozgobolt.feature.vehicleTracking.domain.model.GeoPoint
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.math.pow
import kotlin.random.Random

private const val TEST_PASSWORD = "LoadTestOnly-Passphrase-2026"
private const val FIRST_VENDOR_EMAIL = "loadtest-vendor-001@loadtest.invalid"
private const val SEED = 20_260_516L
private const val SESSION_BACKDATE_SECONDS = 10L
private const val ACCOUNTS_OUTPUT_PATH_ENV = "LOAD_TEST_ACCOUNTS_OUTPUT"

private data class SeededVendor(
    val account: SeededAccount,
    val companyId: Int,
    val regionIndex: Int,
)

private data class SeededAccount(
    val role: UserRole,
    val email: String,
    val userId: Int,
    val accessToken: String,
    val vehicleId: Int? = null,
    val regionIndex: Int,
    val home: GeoPoint? = null,
)

/**
 * Seeds the vendors, vehicles, active assignments, buyers, favorites and saved locations a live
 * load run needs, in a disposable local `mozgobolt_loadtest` database. Trucks are spread evenly
 * across regions, buyers follow the workload's Zipf skew, and every buyer favorites trucks of
 * their own region and saves places near home: that is what makes each telemetry batch do real
 * proximity-alert work instead of finding no candidates.
 */
fun main() =
    runBlocking {
        val databaseUrl = requiredEnvironment("LOAD_TEST_DB_URL")
        val pepper = requiredEnvironment("PASSWORD_PEPPER")
        val tokenManager =
            JwtTokenManager(
                secret = requiredEnvironment("JWT_SECRET"),
                issuer = requiredEnvironment("JWT_ISSUER"),
                audience = requiredEnvironment("JWT_AUDIENCE"),
            )
        val spec = WorkloadSpec.load()
        requireDisposableLocalDatabase(databaseUrl)

        val dataSource =
            DatabaseFactory.createHikariDataSource(
                dbUrl = databaseUrl,
                dbUser = requiredEnvironment("LOAD_TEST_DB_USER"),
                dbPassword = requiredEnvironment("LOAD_TEST_DB_PASSWORD"),
                poolSize = 5,
            )
        try {
            DatabaseFactory.runFlywayMigration(dataSource)
            val tx = ExposedTransactionalRunner(DatabaseFactory.createDatabase(dataSource))
            val users = UserRepositoryI()
            check(tx.transactional { users.findUser(FIRST_VENDOR_EMAIL) } == null) {
                "The load-test database already has fixtures. Recreate the disposable database before reseeding."
            }

            val passwordHash = PasswordServiceImpl(pepper).hashPassword(TEST_PASSWORD)
            val accounts =
                tx.transactional {
                    val vendors = seedVendors(spec, passwordHash, tokenManager, users)
                    vendors.map(SeededVendor::account) + seedBuyers(spec, vendors, passwordHash, tokenManager, users)
                }

            val output = loadTestAccountsOutputPath()
            writeAccounts(output, accounts)
            println(
                "Seeded ${spec.trucks} vendors/active vehicles and ${spec.buyers} buyers " +
                    "across ${spec.regions.size} regions.",
            )
            println("Test-only account list written to $output.")
        } finally {
            (dataSource as? AutoCloseable)?.close()
        }
    }

internal fun loadTestAccountsOutputPath(configuredPath: String? = System.getenv(ACCOUNTS_OUTPUT_PATH_ENV)): Path {
    val output =
        Path
            .of(configuredPath?.takeIf(String::isNotBlank) ?: AccountBook.DEFAULT_PATH)
            .toAbsolutePath()
            .normalize()
    val buildDirectory = Path.of("build/load-test").toAbsolutePath().normalize()
    require(output.startsWith(buildDirectory)) {
        "$ACCOUNTS_OUTPUT_PATH_ENV must point to a file under build/load-test."
    }
    return output
}

private suspend fun seedVendors(
    spec: WorkloadSpec,
    passwordHash: String,
    tokenManager: TokenManager,
    users: UserRepositoryI,
): List<SeededVendor> {
    val companies = CompanyRepositoryI()
    val memberships = CompanyMembershipRepositoryI()
    val vehicles = VehicleRepositoryI()
    val assignments = VehicleAssignmentRepositoryI()
    val regionOfTruck = assignBuckets(spec.trucks, zipfWeights(spec.regions.size, spec.truckRegionSkew))

    return regionOfTruck.mapIndexed { index, regionIndex ->
        val number = index + 1
        val email = "loadtest-vendor-${number.toString().padStart(NUMBER_PADDING, '0')}@loadtest.invalid"
        val vendor = createVerifiedUser(users, email, "Load Test Vendor $number", UserRole.VENDOR, passwordHash)
        val company =
            companies.create(
                name = "Load Test Company $number",
                inviteCode = SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH),
            )
        checkNotNull(memberships.addIfAbsent(company.id, vendor.id, CompanyRole.ADMIN))
        val vehicle =
            checkNotNull(
                vehicles.create(
                    companyId = company.id,
                    label = "Load Test Vehicle $number",
                    licensePlate = "LT${number.toString().padStart(LICENSE_PADDING, '0')}",
                ),
            )
        val startedAt = Instant.now().minusSeconds(SESSION_BACKDATE_SECONDS)
        checkNotNull(
            assignments.startAssignment(
                vehicleId = vehicle.id,
                vendorUserId = vendor.id,
                startedAt = startedAt,
                locationSharingConsentedAt = startedAt,
            ),
        )
        SeededVendor(
            account =
                SeededAccount(
                    role = UserRole.VENDOR,
                    email = email,
                    userId = vendor.id,
                    accessToken = seedActiveSession(vendor.id, vendor.role, tokenManager, users),
                    vehicleId = vehicle.id,
                    regionIndex = regionIndex,
                ),
            companyId = company.id,
            regionIndex = regionIndex,
        )
    }
}

private suspend fun seedBuyers(
    spec: WorkloadSpec,
    vendors: List<SeededVendor>,
    passwordHash: String,
    tokenManager: TokenManager,
    users: UserRepositoryI,
): List<SeededAccount> {
    val favorites = CompanyFavoriteRepositoryI()
    val savedLocations = SavedLocationRepositoryI()
    val companiesByRegion =
        vendors.groupBy(SeededVendor::regionIndex).mapValues { (_, inRegion) ->
            inRegion.map(SeededVendor::companyId)
        }
    val regionOfBuyer = assignBuckets(spec.buyers, zipfWeights(spec.regions.size, spec.buyerRegionSkew))

    return regionOfBuyer.mapIndexed { index, regionIndex ->
        val number = index + 1
        val random = Random(SEED xor number.toLong())
        val email = "loadtest-buyer-${number.toString().padStart(NUMBER_PADDING, '0')}@loadtest.invalid"
        val buyer = createVerifiedUser(users, email, "Load Test Buyer $number", UserRole.BUYER, passwordHash)
        val home =
            spec.regions[regionIndex]
                .center
                .scatteredBy(spec.cityRadiusKm, random)
                .roundedToWirePrecision()

        zipfWeightedSampleWithoutReplacement(
            companiesByRegion[regionIndex].orEmpty(),
            spec.buyerBehavior.favoritesPerBuyer,
            spec.buyerBehavior.favoriteSkew,
            random,
        ).forEach { companyId -> favorites.addIfAbsent(buyer.id, companyId) }
        repeat(spec.buyerBehavior.savedLocationsPerBuyer) { place ->
            val position = if (place == 0) home else home.scatteredBy(spec.cityRadiusKm, random)
            savedLocations.create(
                userId = buyer.id,
                label = "Saved place ${place + 1}",
                latitude = position.latitude,
                longitude = position.longitude,
                radiusKm = spec.buyerBehavior.savedLocationRadiusKm,
            )
        }

        SeededAccount(
            role = UserRole.BUYER,
            email = email,
            userId = buyer.id,
            accessToken = seedActiveSession(buyer.id, buyer.role, tokenManager, users),
            regionIndex = regionIndex,
            home = home,
        )
    }
}

private fun zipfWeightedSampleWithoutReplacement(
    candidates: List<Int>,
    count: Int,
    skew: Double,
    random: Random,
): List<Int> =
    // Efraimidis-Spirakis: each candidate draws u^(1/weight) and the largest keys win, so earlier
    // (more popular) candidates are favoured by a Zipf law while the picks stay distinct.
    candidates
        .zip(zipfWeights(candidates.size, skew))
        .map { (candidate, weight) -> candidate to random.nextDouble().pow(1.0 / weight) }
        .sortedByDescending { (_, key) -> key }
        .take(count)
        .map { (candidate, _) -> candidate }

private suspend fun createVerifiedUser(
    users: UserRepositoryI,
    email: String,
    name: String,
    role: UserRole,
    passwordHash: String,
) = checkNotNull(
    users.createUser(
        email = email,
        password = passwordHash,
        name = name,
        role = role,
        phoneNumber = null,
        whatsappNumber = null,
        viberNumber = null,
        messengerUsername = null,
    ),
).also { users.markEmailVerified(it.id) }

private suspend fun seedActiveSession(
    userId: Int,
    role: UserRole,
    tokenManager: TokenManager,
    users: UserRepositoryI,
): String {
    val familyId = UUID.randomUUID().toString()
    val refreshToken = tokenManager.generateRefreshToken(userId)
    users.saveRefreshToken(
        userId = userId,
        token = tokenManager.hashTokenForStorage(refreshToken),
        familyId = familyId,
    )
    return tokenManager.generateAccessToken(userId, role.name, familyId)
}

private fun writeAccounts(
    output: Path,
    accounts: List<SeededAccount>,
) {
    Files.createDirectories(output.parent)
    val rows =
        accounts.map { account ->
            listOf(
                account.role.name,
                account.email,
                TEST_PASSWORD,
                account.accessToken,
                account.userId.toString(),
                account.vehicleId?.toString().orEmpty(),
                account.regionIndex.toString(),
                account.home
                    ?.latitude
                    ?.toString()
                    .orEmpty(),
                account.home
                    ?.longitude
                    ?.toString()
                    .orEmpty(),
            ).joinToString(",")
        }
    Files.write(output, listOf(AccountBook.CSV_HEADER) + rows)
}

private fun requireDisposableLocalDatabase(databaseUrl: String) {
    val uri = URI.create(databaseUrl.removePrefix("jdbc:"))
    require(uri.host in setOf("localhost", "127.0.0.1", "::1")) {
        "Refusing to seed a non-local database host: ${uri.host}"
    }
    require(uri.path.trim('/') == "mozgobolt_loadtest") {
        "Refusing to seed a database other than mozgobolt_loadtest: ${uri.path}"
    }
}

private fun requiredEnvironment(name: String): String =
    checkNotNull(System.getenv(name)?.takeIf(String::isNotBlank)) {
        "Set $name to use the disposable live-load test seeder."
    }

private const val NUMBER_PADDING = 3
private const val LICENSE_PADDING = 4
