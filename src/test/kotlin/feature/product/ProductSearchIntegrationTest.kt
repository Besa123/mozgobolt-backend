package com.shelflife.feature.product

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ProductRepositoryI.search() builds a raw SQL LIKE query directly against Exposed — none of
 * this is visible to the FakeProductRepository-backed unit tests, so it's only proven here.
 */
class ProductSearchIntegrationTest {
    @Test
    fun `a substring matches case-insensitively against the seeded global dictionary`() {
        skipIfNoDocker()

        withRealProductDatabase { harness ->
            harness.tx.transactional {
                val results = harness.repository.search(userId = 1, query = "SAJ", limit = 20)

                assertTrue(results.any { it.name == "Sajt" })
            }
        }
    }

    @Test
    fun `a private product owned by another user is excluded from search`() {
        skipIfNoDocker()

        withRealProductDatabase { harness ->
            createPrivate(harness, userId = 2, name = "Kolbász Spar")

            harness.tx.transactional {
                val results = harness.repository.search(userId = 1, query = "kolb", limit = 20)

                assertFalse(results.any { it.name == "Kolbász Spar" })
            }
        }
    }

    @Test
    fun `a caller's own private product is included alongside global matches`() {
        skipIfNoDocker()

        withRealProductDatabase { harness ->
            createPrivate(harness, userId = 1, name = "Kolbász Lidl")

            harness.tx.transactional {
                val results = harness.repository.search(userId = 1, query = "kolb", limit = 20)

                assertTrue(results.any { it.name == "Kolbász Lidl" })
            }
        }
    }

    @Test
    fun `a literal percent sign in the query is not treated as a SQL wildcard`() {
        skipIfNoDocker()

        withRealProductDatabase { harness ->
            createPrivate(harness, userId = 1, name = "100% Lean Beef")
            createPrivate(harness, userId = 1, name = "Totally Unrelated")

            harness.tx.transactional {
                // Without escaping, "%" as a raw LIKE wildcard would match every product, not just
                // the one that literally contains "100%".
                val results = harness.repository.search(userId = 1, query = "100%", limit = 20)

                assertEquals(listOf("100% Lean Beef"), results.map { it.name })
            }
        }
    }

    @Test
    fun `a literal underscore in the query is not treated as a single-character SQL wildcard`() {
        skipIfNoDocker()

        withRealProductDatabase { harness ->
            createPrivate(harness, userId = 1, name = "under_score")
            createPrivate(harness, userId = 1, name = "underAscore")

            harness.tx.transactional {
                // "_" is a single-character SQL wildcard; unescaped it would also match "underAscore".
                val results = harness.repository.search(userId = 1, query = "under_score", limit = 20)

                assertEquals(listOf("under_score"), results.map { it.name })
            }
        }
    }

    @Test
    fun `results are ordered alphabetically`() {
        skipIfNoDocker()

        withRealProductDatabase { harness ->
            createPrivate(harness, userId = 1, name = "Zebra Snack")
            createPrivate(harness, userId = 1, name = "Apple Snack")
            createPrivate(harness, userId = 1, name = "Mango Snack")

            harness.tx.transactional {
                val results = harness.repository.search(userId = 1, query = "Snack", limit = 20)

                assertEquals(listOf("Apple Snack", "Mango Snack", "Zebra Snack"), results.map { it.name })
            }
        }
    }

    @Test
    fun `the limit parameter is respected and repeated calls return the same subset`() {
        skipIfNoDocker()

        withRealProductDatabase { harness ->
            repeat(5) { index -> createPrivate(harness, userId = 1, name = "Limit Test $index") }

            harness.tx.transactional {
                // Without a deterministic ORDER BY, an unordered LIMIT can return a different
                // arbitrary subset on every call — this proves it doesn't.
                val first = harness.repository.search(userId = 1, query = "Limit Test", limit = 2)
                val second = harness.repository.search(userId = 1, query = "Limit Test", limit = 2)

                assertEquals(2, first.size)
                assertEquals(first.map { it.name }, second.map { it.name })
            }
        }
    }

    private suspend fun createPrivate(
        harness: ProductTestHarness,
        userId: Int,
        name: String,
    ) {
        harness.service.createPrivateProduct(
            userId = userId,
            name = name,
            defaultLifespanDays = null,
            defaultUnitCategory = null,
        )
    }
}
