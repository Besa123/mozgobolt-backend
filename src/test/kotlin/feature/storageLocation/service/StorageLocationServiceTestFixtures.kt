package com.shelflife.feature.storageLocation.service

import com.shelflife.core.database.TransactionalRunner
import com.shelflife.feature.storageLocation.domain.StorageLocationRepository
import com.shelflife.feature.storageLocation.domain.model.RenameLocationOutcome
import com.shelflife.feature.storageLocation.domain.model.StorageLocation
import com.shelflife.feature.sync.service.NoOpSyncService

data class Harness(
    val service: StorageLocationServiceI,
    val repository: FakeStorageLocationRepository,
)

fun newHarness(): Harness {
    val repository = FakeStorageLocationRepository()
    val service =
        StorageLocationServiceI(
            storageLocationRepository = repository,
            syncService = NoOpSyncService(),
            tx = NoopTransactionalRunner(),
        )
    return Harness(service, repository)
}

class NoopTransactionalRunner : TransactionalRunner {
    override suspend fun <T> transactional(block: suspend () -> T): T = block()
}

// Name comparisons are deliberately case-sensitive here, mirroring the real uq_user_location_name
// constraint (unlike products, storage locations were never given a case-insensitive index).
class FakeStorageLocationRepository : StorageLocationRepository {
    private val locationsById = mutableMapOf<Int, StorageLocation>()
    private var nextId = 1

    fun seed(
        userId: Int,
        name: String,
    ): StorageLocation {
        val location = StorageLocation(id = nextId++, userId = userId, name = name)
        locationsById[location.id] = location
        return location
    }

    override suspend fun findByIdAndUserId(
        id: Int,
        userId: Int,
    ): StorageLocation? = locationsById[id]?.takeIf { it.userId == userId }

    override suspend fun findAllByUserId(userId: Int): List<StorageLocation> =
        locationsById.values.filter { it.userId == userId }.sortedBy { it.name }

    override suspend fun create(
        userId: Int,
        name: String,
    ): StorageLocation? {
        val collides = locationsById.values.any { it.userId == userId && it.name == name }
        if (collides) return null

        return seed(userId, name)
    }

    override suspend fun updateName(
        id: Int,
        userId: Int,
        name: String,
    ): RenameLocationOutcome {
        val location = locationsById[id] ?: return RenameLocationOutcome.NotFound
        if (location.userId != userId) return RenameLocationOutcome.NotFound

        val collides = locationsById.values.any { it.id != id && it.userId == userId && it.name == name }
        if (collides) return RenameLocationOutcome.DuplicateName

        val renamed = location.copy(name = name)
        locationsById[id] = renamed
        return RenameLocationOutcome.Renamed(renamed)
    }

    override suspend fun deleteByIdAndUserId(
        id: Int,
        userId: Int,
    ): Boolean {
        val location = locationsById[id] ?: return false
        if (location.userId != userId) return false

        locationsById.remove(id)
        return true
    }
}
