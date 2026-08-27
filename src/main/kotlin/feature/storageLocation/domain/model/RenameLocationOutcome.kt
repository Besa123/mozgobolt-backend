package com.shelflife.feature.storageLocation.domain.model

sealed interface RenameLocationOutcome {
    data class Renamed(
        val location: StorageLocation,
    ) : RenameLocationOutcome

    data object NotFound : RenameLocationOutcome

    data object DuplicateName : RenameLocationOutcome
}
