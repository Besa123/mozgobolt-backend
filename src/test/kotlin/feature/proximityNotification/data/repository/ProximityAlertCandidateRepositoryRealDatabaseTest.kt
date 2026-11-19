/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.proximityNotification.data.repository

import com.mozgobolt.core.domain.security.SecureTokenGenerator
import com.mozgobolt.core.skipIfNoDocker
import com.mozgobolt.core.withRealDatabase
import com.mozgobolt.feature.company.data.repository.CompanyRepositoryI
import com.mozgobolt.feature.company.domain.model.CompanyConstraints
import com.mozgobolt.feature.companyFavorite.data.repository.CompanyFavoriteRepositoryI
import com.mozgobolt.feature.savedLocation.data.repository.SavedLocationRepositoryI
import kotlin.test.Test
import kotlin.test.assertEquals

class ProximityAlertCandidateRepositoryRealDatabaseTest {
    @Test
    fun `candidate query returns saved locations only for users favoriting the requested company`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val companyRepository = CompanyRepositoryI()
            val favoriteRepository = CompanyFavoriteRepositoryI()
            val savedLocationRepository = SavedLocationRepositoryI()
            val candidateRepository = ProximityAlertCandidateRepositoryI()
            val (companyId, expectedLocationIds) =
                tx.transactional {
                    val company =
                        companyRepository.create(
                            name = "Candidate query company",
                            inviteCode =
                                SecureTokenGenerator.generate(
                                    CompanyConstraints.INVITE_CODE_BYTE_LENGTH,
                                ),
                        )
                    val otherCompany =
                        companyRepository.create(
                            name = "Other candidate company",
                            inviteCode =
                                SecureTokenGenerator.generate(
                                    CompanyConstraints.INVITE_CODE_BYTE_LENGTH,
                                ),
                        )
                    favoriteRepository.addIfAbsent(userId = 1, companyId = company.id)
                    favoriteRepository.addIfAbsent(userId = 2, companyId = otherCompany.id)
                    val firstLocation =
                        savedLocationRepository.create(
                            userId = 1,
                            label = "Home",
                            latitude = 47.4979,
                            longitude = 19.0402,
                            radiusKm = 1.0,
                        )
                    val secondLocation =
                        savedLocationRepository.create(
                            userId = 1,
                            label = "Work",
                            latitude = 47.5,
                            longitude = 19.05,
                            radiusKm = 2.0,
                        )
                    savedLocationRepository.create(
                        userId = 2,
                        label = "Other company",
                        latitude = 47.6,
                        longitude = 19.1,
                        radiusKm = 3.0,
                    )
                    company.id to setOf(firstLocation.id, secondLocation.id)
                }

            val candidates =
                tx.transactional {
                    candidateRepository.findSavedLocationsForCompany(companyId)
                }

            assertEquals(expectedLocationIds, candidates.map { it.id }.toSet())
            assertEquals(setOf(1), candidates.map { it.userId }.toSet())
        }
    }
}
