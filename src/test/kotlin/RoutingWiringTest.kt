package com.mozgobolt

import com.mozgobolt.core.configureTestEnvironment
import com.mozgobolt.core.data.idempotency.FakeIdempotencyStore
import com.mozgobolt.core.data.idempotency.IdempotencyStore
import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.core.installTestModules
import com.mozgobolt.feature.company.domain.CompanyService
import com.mozgobolt.feature.company.domain.model.Company
import com.mozgobolt.feature.company.domain.model.CompanyError
import com.mozgobolt.feature.company.domain.model.CompanyMembership
import com.mozgobolt.feature.companyFavorite.domain.CompanyFavoriteService
import com.mozgobolt.feature.companyFavorite.domain.model.CompanyFavorite
import com.mozgobolt.feature.companyFavorite.domain.model.CompanyFavoriteError
import com.mozgobolt.feature.deviceInstallation.domain.DeviceInstallationService
import com.mozgobolt.feature.deviceInstallation.domain.model.DeviceInstallation
import com.mozgobolt.feature.deviceInstallation.domain.model.DeviceInstallationError
import com.mozgobolt.feature.deviceInstallation.domain.model.DevicePlatform
import com.mozgobolt.feature.savedLocation.domain.SavedLocationService
import com.mozgobolt.feature.savedLocation.domain.model.SavedLocationError
import com.mozgobolt.feature.savedLocation.domain.model.UserSavedLocation
import com.mozgobolt.feature.sync.domain.SyncEventHub
import com.mozgobolt.feature.sync.domain.SyncService
import com.mozgobolt.feature.sync.domain.model.SyncHint
import com.mozgobolt.feature.sync.routing.FakeSyncService
import com.mozgobolt.feature.user.domain.UserService
import com.mozgobolt.feature.user.routing.FakeUserService
import com.mozgobolt.feature.vehicle.domain.VehicleService
import com.mozgobolt.feature.vehicle.domain.model.Vehicle
import com.mozgobolt.feature.vehicle.domain.model.VehicleError
import com.mozgobolt.feature.vehicleAssignment.domain.VehicleAssignmentService
import com.mozgobolt.feature.vehicleAssignment.domain.model.VehicleAssignment
import com.mozgobolt.feature.vehicleAssignment.domain.model.VehicleAssignmentError
import com.mozgobolt.feature.vehiclePing.domain.VehiclePingHub
import com.mozgobolt.feature.vehiclePing.domain.VehiclePingService
import com.mozgobolt.feature.vehiclePing.domain.model.PingError
import com.mozgobolt.feature.vehiclePing.domain.model.VehiclePing
import com.mozgobolt.feature.vehiclePing.service.InMemoryVehiclePingHub
import com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationHub
import com.mozgobolt.feature.vehicleTracking.domain.VehicleTrackingService
import com.mozgobolt.feature.vehicleTracking.domain.model.TelemetryError
import com.mozgobolt.feature.vehicleTracking.domain.model.TelemetryPoint
import com.mozgobolt.feature.vehicleTracking.service.H3CellIndexer
import com.mozgobolt.feature.vehicleTracking.service.InMemoryVehicleLocationHub
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.io.PrintWriter
import java.sql.Connection
import java.sql.SQLFeatureNotSupportedException
import java.time.Instant
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertNotEquals

class RoutingWiringTest {
    @Test
    fun `every feature's routes are actually registered on the real routing tree`() =
        testApplication {
            configureTestEnvironment()
            application {
                installTestModules()
                dependencies {
                    provide<UserService> { FakeUserService() }
                    provide<SyncService> { FakeSyncService() }
                    provide<SyncEventHub> { FakeSyncEventHub() }
                    provide<CompanyService> { FakeCompanyService() }
                    provide<CompanyFavoriteService> { FakeCompanyFavoriteService() }
                    provide<SavedLocationService> { FakeSavedLocationService() }
                    provide<VehicleService> { FakeVehicleService() }
                    provide<VehicleAssignmentService> { FakeVehicleAssignmentService() }
                    provide<VehicleLocationHub> { InMemoryVehicleLocationHub(H3CellIndexer()) }
                    provide<VehicleTrackingService> { FakeVehicleTrackingService() }
                    provide<VehiclePingService> { FakeVehiclePingService() }
                    provide<VehiclePingHub> { InMemoryVehiclePingHub() }
                    provide<DeviceInstallationService> { FakeDeviceInstallationService() }
                    provide<IdempotencyStore> { FakeIdempotencyStore() }
                    provide<DataSource> { FakeDataSource() }
                }
                configureRouting()
            }

            val protectedGetPaths =
                listOf(
                    "/api/v1/sync",
                    "/api/v1/companies/1/vehicles",
                    "/api/v1/vehicles/1",
                    "/api/v1/vehicles/1/picture",
                    "/api/v1/companies/1/members",
                    "/api/v1/favorites",
                    "/api/v1/saved-locations",
                )

            val protectedDeletePaths = listOf("/api/v1/device-installations/some-fid")

            protectedGetPaths.forEach { path ->
                val response = client.get(path)
                assertNotEquals(HttpStatusCode.NotFound, response.status, "expected $path to be a registered route")
            }

            val protectedPostPaths =
                listOf(
                    "/api/v1/companies",
                    "/api/v1/companies/join",
                    "/api/v1/companies/1/invite-code/regenerate",
                    "/api/v1/companies/1/vehicles",
                    "/api/v1/companies/1/vehicles/1/archive",
                    "/api/v1/companies/1/vehicles/1/picture",
                    "/api/v1/companies/1/members/1/promote",
                    "/api/v1/companies/1/members/1/demote",
                    "/api/v1/companies/1/favorite",
                    "/api/v1/saved-locations",
                    "/api/v1/vehicles/1/link",
                    "/api/v1/vehicles/1/unlink",
                    "/api/v1/vehicles/1/assignments/end-active",
                    "/api/v1/vehicle-tracking/telemetry",
                    "/api/v1/vehicles/1/ping",
                    "/api/v1/device-installations",
                )

            protectedPostPaths.forEach { path ->
                val response = client.post(path)
                assertNotEquals(HttpStatusCode.NotFound, response.status, "expected $path to be a registered route")
            }

            protectedDeletePaths.forEach { path ->
                val response = client.delete(path)
                assertNotEquals(HttpStatusCode.NotFound, response.status, "expected $path to be a registered route")
            }
        }
}

private class FakeSyncEventHub : SyncEventHub {
    override fun publish(
        userId: Int,
        eventId: Long,
        originDeviceId: String?,
    ) = Unit

    override fun subscribe(userId: Int): Flow<SyncHint> = emptyFlow()
}

private class FakeCompanyService : CompanyService {
    override suspend fun createCompany(
        creatorUserId: Int,
        name: String,
    ): AppResult<Company, CompanyError> = AppResult.Error(CompanyError.NOT_A_MEMBER)

    override suspend fun joinCompany(
        userId: Int,
        inviteCode: String,
    ): AppResult<Company, CompanyError> = AppResult.Error(CompanyError.INVALID_INVITE_CODE)

    override suspend fun renameCompany(
        adminUserId: Int,
        companyId: Int,
        newName: String,
    ): AppResult<Company, CompanyError> = AppResult.Error(CompanyError.NOT_ADMIN)

    override suspend fun deleteCompany(
        adminUserId: Int,
        companyId: Int,
    ): AppResult<Unit, CompanyError> = AppResult.Error(CompanyError.NOT_ADMIN)

    override suspend fun regenerateInviteCode(
        adminUserId: Int,
        companyId: Int,
    ): AppResult<Company, CompanyError> = AppResult.Error(CompanyError.NOT_ADMIN)

    override suspend fun listMembers(
        userId: Int,
        companyId: Int,
    ): AppResult<List<CompanyMembership>, CompanyError> = AppResult.Error(CompanyError.NOT_A_MEMBER)

    override suspend fun promoteMember(
        adminUserId: Int,
        companyId: Int,
        targetUserId: Int,
    ): AppResult<Unit, CompanyError> = AppResult.Error(CompanyError.NOT_ADMIN)

    override suspend fun demoteMember(
        adminUserId: Int,
        companyId: Int,
        targetUserId: Int,
    ): AppResult<Unit, CompanyError> = AppResult.Error(CompanyError.NOT_ADMIN)

    override suspend fun removeMember(
        adminUserId: Int,
        companyId: Int,
        targetUserId: Int,
    ): AppResult<Unit, CompanyError> = AppResult.Error(CompanyError.NOT_ADMIN)

    override suspend fun leaveCompany(
        userId: Int,
        companyId: Int,
    ): AppResult<Unit, CompanyError> = AppResult.Error(CompanyError.NOT_A_MEMBER)
}

private class FakeCompanyFavoriteService : CompanyFavoriteService {
    override suspend fun favoriteCompany(
        userId: Int,
        companyId: Int,
    ): AppResult<Unit, CompanyFavoriteError> = AppResult.Error(CompanyFavoriteError.COMPANY_NOT_FOUND)

    override suspend fun unfavoriteCompany(
        userId: Int,
        companyId: Int,
    ): AppResult<Unit, CompanyFavoriteError> = AppResult.Success(Unit)

    override suspend fun listFavorites(userId: Int): List<CompanyFavorite> = emptyList()
}

private class FakeSavedLocationService : SavedLocationService {
    override suspend fun createSavedLocation(
        userId: Int,
        label: String,
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
    ): UserSavedLocation =
        UserSavedLocation(
            id = 1,
            userId = userId,
            label = label,
            latitude = latitude,
            longitude = longitude,
            radiusKm = radiusKm,
            createdAt = Instant.now(),
        )

    override suspend fun listSavedLocations(userId: Int): List<UserSavedLocation> = emptyList()

    override suspend fun deleteSavedLocation(
        userId: Int,
        savedLocationId: Int,
    ): AppResult<Unit, SavedLocationError> = AppResult.Error(SavedLocationError.NOT_FOUND)
}

private class FakeVehicleService : VehicleService {
    override suspend fun createVehicle(
        adminUserId: Int,
        companyId: Int,
        label: String,
        licensePlate: String,
        pictureUrl: String?,
    ): AppResult<Vehicle, VehicleError> = AppResult.Error(VehicleError.NOT_A_MEMBER)

    override suspend fun updateVehicle(
        adminUserId: Int,
        companyId: Int,
        vehicleId: Int,
        licensePlate: String,
        pictureUrl: String?,
    ): AppResult<Vehicle, VehicleError> = AppResult.Error(VehicleError.NOT_A_MEMBER)

    override suspend fun listCompanyVehicles(
        userId: Int,
        companyId: Int,
    ): AppResult<List<Vehicle>, VehicleError> = AppResult.Error(VehicleError.NOT_A_MEMBER)

    override suspend fun findVehicle(vehicleId: Int): AppResult<Vehicle, VehicleError> =
        AppResult.Error(VehicleError.VEHICLE_NOT_FOUND)

    override suspend fun archiveVehicle(
        adminUserId: Int,
        companyId: Int,
        vehicleId: Int,
    ): AppResult<Unit, VehicleError> = AppResult.Error(VehicleError.NOT_A_MEMBER)

    override suspend fun uploadVehiclePicture(
        adminUserId: Int,
        companyId: Int,
        vehicleId: Int,
        rawBytes: ByteArray,
    ): AppResult<Vehicle, VehicleError> = AppResult.Error(VehicleError.NOT_A_MEMBER)

    override suspend fun getVehiclePicture(vehicleId: Int): AppResult<ByteArray, VehicleError> =
        AppResult.Error(VehicleError.VEHICLE_NOT_FOUND)
}

private class FakeVehicleAssignmentService : VehicleAssignmentService {
    override suspend fun link(
        vendorUserId: Int,
        vehicleId: Int,
    ): AppResult<VehicleAssignment, VehicleAssignmentError> = AppResult.Error(VehicleAssignmentError.VEHICLE_NOT_FOUND)

    override suspend fun unlink(
        vendorUserId: Int,
        vehicleId: Int,
    ): AppResult<Unit, VehicleAssignmentError> = AppResult.Error(VehicleAssignmentError.NOT_LINKED)

    override suspend fun endActiveAssignment(
        adminUserId: Int,
        vehicleId: Int,
    ): AppResult<Unit, VehicleAssignmentError> = AppResult.Error(VehicleAssignmentError.NOT_ADMIN)

    override suspend fun endActiveAssignmentForVendorInCompany(
        vendorUserId: Int,
        companyId: Int,
    ) = Unit

    override suspend fun endAllActiveAssignmentsForCompany(companyId: Int) = Unit

    override suspend fun findActiveForVendor(vendorUserId: Int): VehicleAssignment? = null

    override suspend fun findActiveForVehicle(vehicleId: Int): VehicleAssignment? = null
}

private class FakeVehicleTrackingService : VehicleTrackingService {
    override suspend fun ingestTelemetry(
        vendorUserId: Int,
        points: List<TelemetryPoint>,
    ): AppResult<Unit, TelemetryError> = AppResult.Error(TelemetryError.NOT_LINKED_TO_VEHICLE)
}

private class FakeVehiclePingService : VehiclePingService {
    override suspend fun ping(
        buyerUserId: Int,
        vehicleId: Int,
        latitude: Double,
        longitude: Double,
    ): AppResult<VehiclePing, PingError> = AppResult.Error(PingError.VEHICLE_NOT_FOUND)
}

private class FakeDeviceInstallationService : DeviceInstallationService {
    override suspend fun registerInstallation(
        userId: Int,
        installationId: String,
        platform: DevicePlatform,
    ): DeviceInstallation = DeviceInstallation(1, userId, installationId, platform, Instant.now(), Instant.now())

    override suspend fun unregisterInstallation(
        userId: Int,
        installationId: String,
    ): AppResult<Unit, DeviceInstallationError> = AppResult.Success(Unit)

    override suspend fun sendPush(
        userId: Int,
        data: Map<String, String>,
    ) = Unit
}

private class FakeDataSource : DataSource {
    override fun getConnection(): Connection = throw UnsupportedOperationException()

    override fun getConnection(
        username: String?,
        password: String?,
    ): Connection = throw UnsupportedOperationException()

    override fun getLogWriter(): PrintWriter = throw UnsupportedOperationException()

    override fun setLogWriter(out: PrintWriter?) = throw UnsupportedOperationException()

    override fun setLoginTimeout(seconds: Int) = throw UnsupportedOperationException()

    override fun getLoginTimeout(): Int = throw UnsupportedOperationException()

    override fun getParentLogger(): Logger = throw SQLFeatureNotSupportedException()

    override fun <T : Any?> unwrap(iface: Class<T>?): T = throw UnsupportedOperationException()

    override fun isWrapperFor(iface: Class<*>?): Boolean = false
}
