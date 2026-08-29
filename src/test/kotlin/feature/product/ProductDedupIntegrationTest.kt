package com.shelflife.feature.product

import com.shelflife.core.domain.AppResult
import com.shelflife.core.skipIfNoDocker
import com.shelflife.feature.product.domain.model.ProductError
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The case-insensitive product-name dedup (decision 4, docs/domain/pantry.md) lives only in the
 * V3 migration's partial unique index — Exposed's DSL can't express it, so it's invisible to unit
 * tests. This exercises it against a real Postgres to prove it actually works.
 */
class ProductDedupIntegrationTest {
    @Test
    fun `a private product colliding case-insensitively with a seeded global product is rejected`() {
        skipIfNoDocker()

        withRealProductDatabase { harness ->
            // "Sajt" ships as a seeded global product (V3 migration). `name` arrives at the
            // service already whitespace-checked by `validateDisplayName` (which rejects, rather
            // than trims, leading/trailing whitespace) at the DTO boundary.
            val result = harness.service.createPrivateProduct(1, "sajt", null, null)

            assertEquals(AppResult.Error(ProductError.DUPLICATE_NAME), result)
        }
    }

    @Test
    fun `a private product colliding case-insensitively with the caller's own existing product is rejected`() {
        skipIfNoDocker()

        withRealProductDatabase { harness ->
            // Deliberately ASCII (unlike "Kolbász" elsewhere in this file) — Postgres's lower()
            // case-folding behavior for non-ASCII characters depends on the container's locale, so
            // an accented word isn't a reliable choice for asserting a *cross-case* collision.
            val first = harness.service.createPrivateProduct(1, "Bacon", null, null)
            assertEquals(true, first is AppResult.Success, "seed product must be created: $first")

            val second = harness.service.createPrivateProduct(1, "BACON", null, null)

            assertEquals(AppResult.Error(ProductError.DUPLICATE_NAME), second)
        }
    }

    @Test
    fun `two different users can each privately own a product with the same name`() {
        skipIfNoDocker()

        withRealProductDatabase { harness ->
            val first = harness.service.createPrivateProduct(1, "Kolbász", null, null)
            val second = harness.service.createPrivateProduct(2, "Kolbász", null, null)

            assertEquals(true, first is AppResult.Success)
            assertEquals(true, second is AppResult.Success)
        }
    }

    @Test
    fun `two concurrent requests for the same user and name only let one succeed`() {
        skipIfNoDocker()

        withRealProductDatabase { harness ->
            coroutineScope {
                val results =
                    listOf(
                        async { harness.service.createPrivateProduct(1, "Kolbász", null, null) },
                        async { harness.service.createPrivateProduct(1, "Kolbász", null, null) },
                    ).awaitAll()

                val successes = results.count { it is AppResult.Success }
                val duplicates =
                    results.count { it is AppResult.Error && it.errorType == ProductError.DUPLICATE_NAME }

                assertEquals(1, successes, "exactly one concurrent create should win: $results")
                assertEquals(1, duplicates, "the loser should see a clean DUPLICATE_NAME, not crash: $results")
            }
        }
    }
}
