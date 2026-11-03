/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.company.data.repository

import com.mozgobolt.core.skipIfNoDocker
import com.mozgobolt.core.withRealDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CompanyRepositoryRealDatabaseTest {
    @Test
    fun `company lookup updates and delete reflect persisted rows and missing ids`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val repository = CompanyRepositoryI()
            val company =
                tx.transactional {
                    repository.create(name = "First name", inviteCode = "INVITE01")
                }

            assertEquals(company.id, tx.transactional { repository.findById(company.id)?.id })
            assertEquals(company.id, tx.transactional { repository.findByInviteCode("INVITE01")?.id })
            assertNull(tx.transactional { repository.findById(-1) })
            assertNull(tx.transactional { repository.findByInviteCode("MISSING1") })
            assertNull(tx.transactional { repository.rename(-1, "Missing company") })
            assertNull(tx.transactional { repository.updateInviteCode(-1, "MISSING2") })

            val renamed = tx.transactional { repository.rename(company.id, "Renamed company") }
            assertEquals("Renamed company", renamed?.name)
            val inviteUpdated = tx.transactional { repository.updateInviteCode(company.id, "INVITE02") }
            assertEquals("INVITE02", inviteUpdated?.inviteCode)
            assertNull(tx.transactional { repository.findByInviteCode("INVITE01") })

            assertEquals(true, tx.transactional { repository.delete(company.id) })
            assertEquals(false, tx.transactional { repository.delete(company.id) })
            assertNull(tx.transactional { repository.findById(company.id) })
        }
    }

    @Test
    fun `SQL-like invite codes are matched as literal data`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val repository = CompanyRepositoryI()
            val company =
                tx.transactional {
                    repository.create(name = "Safe company", inviteCode = "INVITE01")
                }

            assertNull(tx.transactional { repository.findByInviteCode("' OR 1=1 --") })
            assertEquals(company.id, tx.transactional { repository.findByInviteCode("INVITE01")?.id })
        }
    }
}
