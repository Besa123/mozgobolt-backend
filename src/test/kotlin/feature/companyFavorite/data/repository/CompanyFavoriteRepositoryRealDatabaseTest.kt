/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.companyFavorite.data.repository

import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.core.domain.security.SecureTokenGenerator
import com.mozgobolt.core.skipIfNoDocker
import com.mozgobolt.core.withRealDatabase
import com.mozgobolt.feature.company.data.repository.CompanyRepositoryI
import com.mozgobolt.feature.company.domain.CompanyRepository
import com.mozgobolt.feature.company.domain.model.Company
import com.mozgobolt.feature.company.domain.model.CompanyConstraints
import com.mozgobolt.feature.companyFavorite.domain.model.CompanyFavoriteError
import com.mozgobolt.feature.companyFavorite.service.CompanyFavoriteServiceI
import com.mozgobolt.feature.proximityNotification.data.repository.ProximityNotificationRepositoryI
import com.mozgobolt.feature.savedLocation.data.repository.SavedLocationRepositoryI
import com.mozgobolt.feature.sync.data.database.SyncEventsTable
import com.mozgobolt.feature.sync.data.repository.SyncRepositoryI
import com.mozgobolt.feature.sync.domain.model.SyncEntityType
import com.mozgobolt.feature.sync.domain.model.SyncOperation
import com.mozgobolt.feature.sync.routing.FakeSyncService
import com.mozgobolt.feature.sync.service.SyncServiceI
import com.mozgobolt.feature.user.data.database.UsersTable
import com.mozgobolt.feature.vehicle.data.repository.VehicleRepositoryI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Proves the real `UNIQUE(user_id, company_id)` index on `company_favorites` actually fires
 * under genuine Postgres concurrency, backing
 * [com.mozgobolt.feature.companyFavorite.service.CompanyFavoriteServiceI]'s idempotent-favorite
 * behavior — exactly the class of bug invisible to fakes, only caught by a real DB.
 */
class CompanyFavoriteRepositoryRealDatabaseTest {
    private class ObservedCompanyRepository(
        private val delegate: CompanyRepository,
        private val lookupStarted: CompletableDeferred<Unit>,
        private val lookupCompleted: CompletableDeferred<Company?>,
    ) : CompanyRepository by delegate {
        override suspend fun findById(companyId: Int): Company? = observe { delegate.findById(companyId) }

        override suspend fun findByIdForUpdate(companyId: Int): Company? =
            observe { delegate.findByIdForUpdate(companyId) }

        private suspend fun observe(lookup: suspend () -> Company?): Company? {
            lookupStarted.complete(Unit)
            val company = lookup()
            lookupCompleted.complete(company)
            return company
        }
    }

    @Test
    fun `two concurrent favorites of the same company by the same user only ever insert one row`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val favoriteRepository = CompanyFavoriteRepositoryI()
            val readyTransactions = AtomicInteger()
            val bothTransactionsReady = CompletableDeferred<Unit>()
            val companyId =
                tx.transactional {
                    CompanyRepositoryI()
                        .create(
                            name = "FamilyFrost",
                            inviteCode = SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH),
                        ).id
                }

            val results =
                coroutineScope {
                    suspend fun addConcurrently() =
                        tx.transactional {
                            if (readyTransactions.incrementAndGet() == 2) bothTransactionsReady.complete(Unit)
                            withTimeout(5_000) { bothTransactionsReady.await() }
                            favoriteRepository.addIfAbsent(1, companyId)
                        }

                    val first = async(Dispatchers.IO) { addConcurrently() }
                    val second = async(Dispatchers.IO) { addConcurrently() }
                    listOf(first.await(), second.await())
                }

            val successes = results.count { it != null }
            assertEquals(1, successes, "exactly one concurrent favorite should win: $results")

            val allFavorites = tx.transactional { favoriteRepository.findAllForUser(1) }
            assertEquals(1, allFavorites.size, "only one row must actually exist, regardless of which call won")
            assertNotNull(allFavorites.firstOrNull { it.companyId == companyId })
        }
    }

    @Test
    fun `concurrent service favorites persist one row and one sync event`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val companyId =
                tx.transactional {
                    CompanyRepositoryI()
                        .create(
                            name = "FamilyFrost",
                            inviteCode = SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH),
                        ).id
                }
            val favoriteRepository = CompanyFavoriteRepositoryI()
            val service =
                CompanyFavoriteServiceI(
                    favoriteRepository = favoriteRepository,
                    proximityNotificationRepository = ProximityNotificationRepositoryI(),
                    companyRepository = CompanyRepositoryI(),
                    syncService = SyncServiceI(SyncRepositoryI(), tx),
                    tx = tx,
                )
            val readyRequests = AtomicInteger()
            val bothRequestsReady = CompletableDeferred<Unit>()

            val results =
                coroutineScope {
                    suspend fun favoriteConcurrently(): AppResult<Unit, CompanyFavoriteError> {
                        if (readyRequests.incrementAndGet() == 2) bothRequestsReady.complete(Unit)
                        withTimeout(5_000) { bothRequestsReady.await() }
                        return service.favoriteCompany(1, companyId)
                    }

                    val first = async(Dispatchers.IO) { favoriteConcurrently() }
                    val second = async(Dispatchers.IO) { favoriteConcurrently() }
                    listOf(first.await(), second.await())
                }

            results.forEach { result ->
                result.fold(
                    onSuccess = {},
                    onError = { fail("expected concurrent favorite to succeed but got $it") },
                )
            }

            assertEquals(
                1,
                tx.transactional { favoriteRepository.findAllForUser(1).count { it.companyId == companyId } },
            )
            val favoriteEvents =
                tx.transactional {
                    SyncEventsTable
                        .selectAll()
                        .where {
                            (SyncEventsTable.userId eq 1) and
                                (SyncEventsTable.entityType eq SyncEntityType.COMPANY_FAVORITE.name) and
                                (SyncEventsTable.entityId eq companyId)
                        }.toList()
                }
            assertEquals(1, favoriteEvents.size)
            assertEquals(SyncOperation.UPSERT.name, favoriteEvents.single()[SyncEventsTable.operation])
        }
    }

    @Test
    fun `favorite lookups are scoped by user and company and removal reports whether a row existed`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val favoriteRepository = CompanyFavoriteRepositoryI()
            val companyId =
                tx.transactional {
                    CompanyRepositoryI()
                        .create(
                            name = "FamilyFrost",
                            inviteCode = SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH),
                        ).id
                }

            assertNotNull(tx.transactional { favoriteRepository.addIfAbsent(1, companyId) })
            assertNotNull(tx.transactional { favoriteRepository.addIfAbsent(2, companyId) })
            assertNull(tx.transactional { favoriteRepository.addIfAbsent(1, companyId) })
            assertEquals(2, tx.transactional { favoriteRepository.findUserIdsFavoritingCompany(companyId) }.size)
            assertTrue(tx.transactional { favoriteRepository.findUserIdsFavoritingCompany(companyId + 1) }.isEmpty())
            assertTrue(tx.transactional { favoriteRepository.findAllForUser(3) }.isEmpty())

            assertEquals(true, tx.transactional { favoriteRepository.remove(1, companyId) })
            assertEquals(false, tx.transactional { favoriteRepository.remove(1, companyId) })
            assertEquals(listOf(2), tx.transactional { favoriteRepository.findUserIdsFavoritingCompany(companyId) })
        }
    }

    @Test
    fun `deleting a user cascades only that user's favorites`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val companyRepository = CompanyRepositoryI()
            val favoriteRepository = CompanyFavoriteRepositoryI()
            val companyId =
                tx.transactional {
                    companyRepository
                        .create(
                            name = "FamilyFrost",
                            inviteCode = SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH),
                        ).id
                }
            assertNotNull(tx.transactional { favoriteRepository.addIfAbsent(1, companyId) })
            assertNotNull(tx.transactional { favoriteRepository.addIfAbsent(2, companyId) })

            assertEquals(1, tx.transactional { UsersTable.deleteWhere { UsersTable.id eq 1 } })

            assertTrue(tx.transactional { favoriteRepository.findAllForUser(1) }.isEmpty())
            assertEquals(listOf(2), tx.transactional { favoriteRepository.findUserIdsFavoritingCompany(companyId) })
            assertNotNull(tx.transactional { companyRepository.findById(companyId) })
        }
    }

    @Test
    fun `favorite racing with company deletion returns not found`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val companyRepository = CompanyRepositoryI()
            val companyId =
                tx.transactional {
                    companyRepository
                        .create(
                            name = "FamilyFrost",
                            inviteCode = SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH),
                        ).id
                }
            val lookupStarted = CompletableDeferred<Unit>()
            val lookupCompleted = CompletableDeferred<Company?>()
            val observedCompanyRepository =
                ObservedCompanyRepository(companyRepository, lookupStarted, lookupCompleted)
            val favoriteRepository = CompanyFavoriteRepositoryI()
            val syncService = FakeSyncService()
            val service =
                CompanyFavoriteServiceI(
                    favoriteRepository = favoriteRepository,
                    proximityNotificationRepository = ProximityNotificationRepositoryI(),
                    companyRepository = observedCompanyRepository,
                    syncService = syncService,
                    tx = tx,
                )
            val deletionStarted = CompletableDeferred<Unit>()
            val allowDeletionToCommit = CompletableDeferred<Unit>()

            coroutineScope {
                val deletion =
                    async(Dispatchers.IO) {
                        tx.transactional {
                            assertTrue(companyRepository.delete(companyId))
                            deletionStarted.complete(Unit)
                            allowDeletionToCommit.await()
                        }
                    }

                try {
                    withTimeout(5_000) { deletionStarted.await() }
                    val favorite =
                        async(Dispatchers.IO) {
                            service.favoriteCompany(userId = 1, companyId = companyId)
                        }
                    withTimeout(5_000) { lookupStarted.await() }

                    assertNull(
                        withTimeoutOrNull(250) { lookupCompleted.await() },
                        "the favorite must wait for the in-flight company deletion",
                    )

                    allowDeletionToCommit.complete(Unit)
                    deletion.await()
                    withTimeout(5_000) { lookupCompleted.await() }
                    favorite.await().fold(
                        onSuccess = { fail("expected COMPANY_NOT_FOUND after the concurrent delete") },
                        onError = { assertEquals(CompanyFavoriteError.COMPANY_NOT_FOUND, it) },
                    )
                    assertTrue(tx.transactional { favoriteRepository.findAllForUser(1) }.isEmpty())
                    assertTrue(syncService.recorded.isEmpty())
                } finally {
                    allowDeletionToCommit.complete(Unit)
                }
            }
        }
    }

    @Test
    fun `unfavoriting a company clears only that company's proximity state for that buyer`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val companyRepository = CompanyRepositoryI()
            val vehicleRepository = VehicleRepositoryI()
            val favoriteRepository = CompanyFavoriteRepositoryI()
            val proximityRepository = ProximityNotificationRepositoryI()
            val savedLocationRepository = SavedLocationRepositoryI()
            val now = java.time.Instant.now()
            val ids =
                tx.transactional {
                    val removedCompany =
                        companyRepository.create(
                            "Removed favorite",
                            SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH),
                        )
                    val retainedCompany =
                        companyRepository.create(
                            "Retained favorite",
                            SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH),
                        )
                    val removedVehicle =
                        vehicleRepository.create(removedCompany.id, "Removed vehicle", "REMOVE-PROX")!!
                    val retainedVehicle =
                        vehicleRepository.create(retainedCompany.id, "Retained vehicle", "KEEP-PROX")!!
                    val savedLocation = savedLocationRepository.create(1, "Home", 47.4979, 19.0402, 1.0)
                    favoriteRepository.addIfAbsent(1, removedCompany.id)
                    proximityRepository.recordEntryAttempted(1, savedLocation.id, removedVehicle.id, now)
                    proximityRepository.recordEntryAttempted(1, savedLocation.id, retainedVehicle.id, now)
                    listOf(
                        removedCompany.id,
                        retainedCompany.id,
                        savedLocation.id,
                        removedVehicle.id,
                        retainedVehicle.id,
                    )
                }
            val removedCompanyId = ids[0]
            val retainedCompanyId = ids[1]
            val savedLocationId = ids[2]
            val removedVehicleId = ids[3]
            val retainedVehicleId = ids[4]
            val service =
                CompanyFavoriteServiceI(
                    favoriteRepository = favoriteRepository,
                    proximityNotificationRepository = proximityRepository,
                    companyRepository = companyRepository,
                    syncService = SyncServiceI(SyncRepositoryI(), tx),
                    tx = tx,
                )

            service.unfavoriteCompany(userId = 1, companyId = removedCompanyId).fold(
                onSuccess = {},
                onError = { fail("expected success but got $it") },
            )

            assertNull(tx.transactional { proximityRepository.find(1, savedLocationId, removedVehicleId) })
            assertNotNull(tx.transactional { proximityRepository.find(1, savedLocationId, retainedVehicleId) })
            assertNotNull(tx.transactional { companyRepository.findById(retainedCompanyId) })
        }
    }
}
