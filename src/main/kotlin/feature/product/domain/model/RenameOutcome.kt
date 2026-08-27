package com.shelflife.feature.product.domain.model

sealed interface RenameOutcome {
    data class Renamed(
        val product: Product,
    ) : RenameOutcome

    data object NotFound : RenameOutcome

    data object DuplicateName : RenameOutcome
}
