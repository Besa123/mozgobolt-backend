package com.mozgobolt.feature.companyFavorite.service

import com.mozgobolt.feature.company.domain.CompanyRepository
import com.mozgobolt.feature.company.domain.model.Company
import com.mozgobolt.feature.companyFavorite.domain.CompanyFavoriteRepository
import com.mozgobolt.feature.companyFavorite.domain.model.CompanyFavorite
import com.mozgobolt.feature.companyFavorite.domain.model.CompanyFavoriteError
import com.mozgobolt.feature.sync.domain.model.SyncEntityType
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
        val service =
            CompanyFavoriteServiceI(
                favoriteRepository = favoriteRepository,
                companyRepository = companyRepository,
                syncService = syncService,
                tx = NoopTransactionalRunner(),
            )
    }

    @Test
    fun `favoriting an existing company succeeds and records a sync event`() {
        runBlocking {
            val fx = Fixture()
            fx.companyRepository.seed(1)

            val result = fx.service.favoriteCompany(userId = 10, companyId = 1)

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
            assertEquals(listOf(1), fx.service.listFavorites(10).map { it.companyId })
            assertTrue(fx.syncService.recorded.any { it.entityType == SyncEntityType.COMPANY_FAVORITE })
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
            fx.companyRepository.seed(1)
            fx.service.favoriteCompany(10, 1).fold(onSuccess = {}, onError = { fail("$it") })

            val result = fx.service.favoriteCompany(10, 1)

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
            assertEquals(1, fx.service.listFavorites(10).size)
            assertEquals(1, fx.syncService.recorded.size)
        }
    }

    @Test
    fun `unfavoriting a company that was favorited removes it and records a sync event`() {
        runBlocking {
            val fx = Fixture()
            fx.companyRepository.seed(1)
            fx.service.favoriteCompany(10, 1).fold(onSuccess = {}, onError = { fail("$it") })

            val result = fx.service.unfavoriteCompany(10, 1)

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
            assertTrue(fx.service.listFavorites(10).isEmpty())
            assertTrue(fx.syncService.recorded.any { it.operation.name == "DELETE" })
        }
    }

    @Test
    fun `unfavoriting a company that was never favorited is a no-op success, not an error`() {
        runBlocking {
            val fx = Fixture()

            val result = fx.service.unfavoriteCompany(10, 1)

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
            assertTrue(fx.syncService.recorded.isEmpty(), "nothing was actually removed, so nothing to sync")
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
