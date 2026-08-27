package com.shelflife.feature.pantryEntry.domain.model

/**
 * [Deleted] is not an error — a PATCH that drives quantityAmount to zero or below deletes the
 * row (decision 3, docs/domain/pantry.md) as a deliberate, successful outcome, not a failure.
 */
sealed interface UpdateEntryOutcome {
    data class Updated(
        val entry: PantryEntry,
    ) : UpdateEntryOutcome

    data object Deleted : UpdateEntryOutcome
}
