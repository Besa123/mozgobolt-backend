/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.utility.functions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class CloseAllResourcesTest {
    @Test
    fun `all cleanup actions run and later failures are suppressed on the first`() {
        val primaryFailure = IllegalStateException("first close failed")
        val laterFailure = IllegalArgumentException("second close failed")
        var completedActions = 0

        val failure =
            assertFailsWith<IllegalStateException> {
                closeAllResources(
                    {
                        completedActions++
                        throw primaryFailure
                    },
                    {
                        completedActions++
                        throw laterFailure
                    },
                    { completedActions++ },
                )
            }

        assertSame(primaryFailure, failure)
        assertEquals(listOf(laterFailure), failure.suppressed.toList())
        assertEquals(3, completedActions)
    }
}
