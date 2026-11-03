/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.companyFavorite.service

import com.mozgobolt.feature.company.domain.CompanyRepository
import com.mozgobolt.feature.company.domain.model.Company
import com.mozgobolt.feature.companyFavorite.domain.CompanyFavoriteRepository
import com.mozgobolt.feature.companyFavorite.domain.model.CompanyFavorite
import com.mozgobolt.feature.companyFavorite.domain.model.CompanyFavoriteError
import com.mozgobolt.feature.proximityNotification.domain.ProximityNotificationRepository
import com.mozgobolt.feature.proximityNotification.domain.model.ProximityNotificationRecord
import com.mozgobolt.feature.sync.domain.model.SyncEntityType
import com.mozgobolt.feature.sync.domain.model.SyncOperation
import com.mozgobolt.feature.sync.routing.FakeSyncService
import com.mozgobolt.feature.user.service.NoopTransactionalRunner
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class CompanyFavoriteServiceITest {
    private class FakeCompanyFavoriteRepository : CompanyFavoriteRepository {
        private val favorites = mutableListOf<CompanyFavorite>()
        private var nextId = 1

        override suspend fun addIfAbsent(
            userId: Int,
            companyId: Int,
        ): CompanyFavorite? {
            if (favorites.any { it.userId == userId && it.companyId == companyId }) return null
            val favorite =
                CompanyFavorite(id = nextId++, userId = userId, companyId = companyId, createdAt = Instant.now())
            favorites += favorite
            return favorite
        }

        override suspend fun remove(
            userId: Int,
            companyId: Int,
        ): Boolean = favorites.removeAll { it.userId == userId && it.companyId == companyId }

        override suspend fun findAllForUser(userId: Int): List<CompanyFavorite> =
            favorites.filter { it.userId == userId }

        override suspend fun findUserIdsFavoritingCompany(companyId: Int): List<Int> =
            favorites.filter { it.companyId == companyId }.map { it.userId }
    }

    private class FakeProximityNotificationRepository : ProximityNotificationRepository {
        val deletedFor = mutableListOf<Pair<Int, Int>>()

        override suspend fun find(
            buyerUserId: Int,
            savedLocationId: Int,
            vehicleId: Int,
        ): ProximityNotificationRecord? = error("not exercised by this test")

        override suspend fun recordEntryAttempted(
            buyerUserId: Int,
            savedLocationId: Int,
            vehicleId: Int,
            attemptedAt: Instant,
        ): ProximityNotificationRecord = error("not exercised by this test")

        override suspend fun claimEntryNotification(
            buyerUserId: Int,
            savedLocationId: Int,
            vehicleId: Int,
            attemptedAt: Instant,
            cooldownCutoff: Instant,
        ): Boolean = error("not exercised by this test")

        override suspend fun markOutside(
            buyerUserId: Int,
            savedLocationId: Int,
            vehicleId: Int,
        ) = error("not exercised by this test")

        override suspend fun deleteForUserAndCompany(
            buyerUserId: Int,
            companyId: Int,
        ): Int {
            deletedFor += buyerUserId to companyId
            return 0
        }
    }

    private class FakeCompanyRepository : CompanyRepository {
        private val companiesById = mutableMapOf<Int, Company>()

        fun seed(id: Int): Company {
            val company = Company(id = id, name = "Company $id", inviteCode = "code-$id", createdAt = Instant.now())
            companiesById[id] = company
            return company
        }

        override suspend fun create(
            name: String,
            inviteCode: String,
        ) = error("not exercised by this test")

        override suspend fun findByInviteCode(inviteCode: String) = error("not exercised by this test")

        override suspend fun findById(companyId: Int): Company? = companiesById[companyId]

        override suspend fun findByIdForUpdate(companyId: Int): Company? = findById(companyId)

        override suspend fun rename(
            companyId: Int,
            newName: String,
        ) = error("not exercised by this test")

        override suspend fun updateInviteCode(
            companyId: Int,
            newInviteCode: String,
        ) = error("not exercised by this test")

        override suspend fun delete(companyId: Int) = error("not exercised by this test")
    }

    private class Fixture {
        val companyRepository = FakeCompanyRepository()
        val syncService = FakeSyncService()
        val favoriteRepository = FakeCompanyFavoriteRepository()
        val proximityNotificationRepository = FakeProximityNotificationRepository()
        val service =
            CompanyFavoriteServiceI(
                favoriteRepository = favoriteRepository,
                proximityNotificationRepository = proximityNotificationRepository,
                companyRepository = companyRepository,
                syncService = syncService,
                tx = NoopTransactionalRunner(),
            )
    }

    @Test
    fun `favoriting an existing company succeeds and records a sync event`() {
        runBlocking {
            val fx = Fixture()
            fx.companyRepository.seed(42)

            val result = fx.service.favoriteCompany(userId = 10, companyId = 42)

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
            assertEquals(listOf(42), fx.service.listFavorites(10).map { it.companyId })
            val favoriteEvent = fx.syncService.recorded.single()
            assertEquals(
                Triple(10, SyncEntityType.COMPANY_FAVORITE, 42),
                Triple(favoriteEvent.userId, favoriteEvent.entityType, favoriteEvent.entityId),
            )
            assertEquals(SyncOperation.UPSERT, favoriteEvent.operation)
        }
    }

    @Test
    fun `favoriting a company that doesn't exist is rejected`() {
        runBlocking {
            val fx = Fixture()

            val result = fx.service.favoriteCompany(userId = 10, companyId = 999)

            result.fold(onSuccess = { fail("expected COMPANY_NOT_FOUND but got success") }, onError = {
                assertEquals(CompanyFavoriteError.COMPANY_NOT_FOUND, it)
            })
        }
    }

    @Test
    fun `favoriting the same company twice is idempotent, not an error, and records only one sync event`() {
        runBlocking {
            val fx = Fixture()
            fx.companyRepository.seed(42)
            fx.service.favoriteCompany(10, 42).fold(onSuccess = {}, onError = { fail("$it") })

            val result = fx.service.favoriteCompany(10, 42)

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
            assertEquals(1, fx.service.listFavorites(10).size)
            assertEquals(1, fx.syncService.recorded.size)
            val favoriteEvent = fx.syncService.recorded.single()
            assertEquals(42, favoriteEvent.entityId)
        }
    }

    @Test
    fun `unfavoriting a company that was favorited removes it and records a sync event`() {
        runBlocking {
            val fx = Fixture()
            fx.companyRepository.seed(42)
            fx.service.favoriteCompany(10, 42).fold(onSuccess = {}, onError = { fail("$it") })

            val result = fx.service.unfavoriteCompany(10, 42)

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
            assertTrue(fx.service.listFavorites(10).isEmpty())
            assertEquals(listOf(10 to 42), fx.proximityNotificationRepository.deletedFor)
            val favoriteEvent = fx.syncService.recorded.last()
            assertEquals(
                Triple(10, SyncEntityType.COMPANY_FAVORITE, 42),
                Triple(favoriteEvent.userId, favoriteEvent.entityType, favoriteEvent.entityId),
            )
            assertEquals(SyncOperation.DELETE, favoriteEvent.operation)
        }
    }

    @Test
    fun `unfavoriting a company that was never favorited is a no-op success, not an error`() {
        runBlocking {
            val fx = Fixture()
            fx.companyRepository.seed(42)

            val result = fx.service.unfavoriteCompany(10, 42)

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
            assertTrue(fx.syncService.recorded.isEmpty(), "nothing was actually removed, so nothing to sync")
            assertTrue(fx.proximityNotificationRepository.deletedFor.isEmpty())
        }
    }

    @Test
    fun `unfavoriting a nonexistent company returns company not found`() {
        runBlocking {
            val fx = Fixture()

            val result = fx.service.unfavoriteCompany(10, 999)

            result.fold(
                onSuccess = { fail("expected COMPANY_NOT_FOUND but got success") },
                onError = { assertEquals(CompanyFavoriteError.COMPANY_NOT_FOUND, it) },
            )
            assertTrue(fx.syncService.recorded.isEmpty())
        }
    }

    @Test
    fun `favorites never leak across users`() {
        runBlocking {
            val fx = Fixture()
            fx.companyRepository.seed(1)
            fx.companyRepository.seed(2)
            fx.service.favoriteCompany(10, 1).fold(onSuccess = {}, onError = { fail("$it") })
            fx.service.favoriteCompany(20, 2).fold(onSuccess = {}, onError = { fail("$it") })

            assertEquals(listOf(1), fx.service.listFavorites(10).map { it.companyId })
            assertEquals(listOf(2), fx.service.listFavorites(20).map { it.companyId })
        }
    }

    @Test
    fun `a user with no favorites gets an empty list, not an error`() {
        runBlocking {
            val fx = Fixture()

            assertTrue(fx.service.listFavorites(10).isEmpty())
        }
    }
}
