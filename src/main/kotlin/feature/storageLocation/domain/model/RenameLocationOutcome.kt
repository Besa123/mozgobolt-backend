package com.shelflife.feature.storageLocation.domain.model

sealed class RenameLocationOutcome {
    data class Renamed(
        val location: StorageLocation,
    ) : RenameLocationOutcome()

    object NotFound : RenameLocationOutcome()

    object DuplicateName : RenameLocationOutcome()
}
