# Pantry domain model

`feature/product/` tracks groceries a user buys so nothing quietly expires unnoticed. A user logs a purchase (product,
quantity, unit, optionally where it's stored and a note); the app groups entries by product and shows the
soonest-expiring batch first — the two-week-old milk surfaces before anyone forgets about it.

## Entities

- `storage_locations` — user's own labels (Fridge, Freezer, Pantry...), unique per `(user_id, name)`.
- `quantity_units` — seeded units grouped by `UnitCategory` (`MASS`/`VOLUME`/`PIECE`), each with a `multiplier` to its
  category's base unit (g/ml/db), so same-category quantities can be summed for display.
- `products` — the dictionary of *what things are* (e.g. "Sonka"). Global entries (`user_id IS NULL`) ship with the app;
  users can also add private ones. `default_lifespan_days`/`default_unit_category` are just pre-fill hints.
- `pantry_entries` — one row per actual batch of a product currently in storage.

## Decisions worth knowing before you touch this

1. `PIECE` units = `db` only. No multiplier for doboz/üveg/csomag — a box isn't a fixed piece count, so any conversion
   would be silently wrong. That distinction goes in `brand_or_note` as free text instead.
2. Buying something always creates a new `pantry_entries` row, even if one already matches exactly. Quantity only
   changes via the user explicitly editing that row (using some up, topping up the same batch).
3. No consumed-history / soft delete — a row is deleted once its quantity hits 0. Not solving waste-analytics yet.
4. Product-name dedup (case-insensitive) is two-layered, deliberately, because a single DB constraint can't express the
   full rule: the V3 migration's partial unique indexes stop global-vs-global and same-user-vs-same-user collisions (not
   in `ProductsTable.kt` — Exposed can't express a partial index), but they're scoped disjointly and never check against
   each other. A private name shadowing an *existing global* product (e.g. a user creating their own "Sonka" when the
   app already ships one) is caught only in `ProductServiceI` via `findGlobalByName`, ahead of the DB call — this is a
   service-level check with a real (if practically tiny) race window, not a hard DB guarantee, because Postgres can't
   index "unique across the union of two disjoint partitions." Relatedly: `ProductServiceI.renameProduct` checks
   ownership (`existsOwnedBy`) *before* running this global-name check — an unauthorized caller must get `NOT_FOUND`
   regardless of whether their attempted name happens to collide globally, not learn something about the dictionary for
   a resource they don't own.
5. `expiration_date` is `DATE`, not `timestamp` — it has no time-of-day component.
6. `quantity_units` is fully seeded (closed set). `products` only ships a few examples — most products are expected to
   come from users, not an app-curated catalog.

## What the backend must get right

- Sort: group by product, `expiration_date ASC NULLS LAST`.
- Search: autocomplete over global + the caller's private products together.
- Aggregation: only sum same product + same unit category — never across the `PIECE` distinction in decision 1.
