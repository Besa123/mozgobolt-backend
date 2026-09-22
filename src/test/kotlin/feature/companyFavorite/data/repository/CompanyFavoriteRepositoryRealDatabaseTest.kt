package com.mozgobolt.feature.companyFavorite.data.repository

import com.mozgobolt.core.skipIfNoDocker
import com.mozgobolt.core.withRealDatabase
import com.mozgobolt.feature.company.data.repository.CompanyRepositoryI
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Proves the real `UNIQUE(user_id, company_id)` index on `company_favorites` actually fires
 * under genuine Postgres concurrency, backing
 * [com.mozgobolt.feature.companyFavorite.service.CompanyFavoriteServiceI]'s idempotent-favorite
 * behavior — exactly the class of bug invisible to fakes, only caught by a real DB.
 */
class CompanyFavoriteRepositoryRealDatabaseTest {
    @Test
    fun `two concurrent favorites of the same company by the same user only ever insert one row`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val favoriteRepository = CompanyFavoriteRepositoryI()
            val companyId =
                tx.transactional {
                    CompanyRepositoryI().create(name = "FamilyFrost", inviteCode = "fav-race-code").id
                }

            val results =
                coroutineScope {
                    val first = async { tx.transactional { favoriteRepository.addIfAbsent(1, companyId) } }
                    val second = async { tx.transactional { favoriteRepository.addIfAbsent(1, companyId) } }
                    listOf(first.await(), second.await())
                }

            val successes = results.count { it != null }
            assertEquals(1, successes, "exactly one concurrent favorite should win: $results")

            val allFavorites = tx.transactional { favoriteRepository.findAllForUser(1) }
            assertEquals(1, allFavorites.size, "only one row must actually exist, regardless of which call won")
            assertNotNull(allFavorites.firstOrNull { it.companyId == companyId })
        }
    }
}
