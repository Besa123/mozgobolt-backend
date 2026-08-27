# Pantry domain model

The pantry feature tracks groceries a user buys so nothing quietly expires unnoticed. A user logs a purchase (product,
quantity, unit, optionally where it's stored and a note); the app groups entries by product and shows the
soonest-expiring batch first — the two-week-old milk surfaces before anyone forgets about it. It spans four features:
`feature/product/`, `feature/storageLocation/`, `feature/quantityUnit/`, `feature/pantryEntry/` — read this before
changing anything in any of them.

## Entities

- `storage_locations` (`feature/storageLocation/`) — user's own labels (Fridge, Freezer, Pantry...), unique per
  `(user_id, name)`.
- `quantity_units` (`feature/quantityUnit/`) — seeded units grouped by `UnitCategory` (`MASS`/`VOLUME`/`PIECE`), each
  with a `multiplier` to its category's base unit (g/ml/db), so same-category quantities can be summed for display.
- `products` (`feature/product/`) — the dictionary of *what things are* (e.g. "Sonka"). Global entries
  (`user_id IS NULL`) ship with the app; users can also add private ones. `default_lifespan_days`/
  `default_unit_category` are just pre-fill hints.
- `pantry_entries` (`feature/pantryEntry/`) — one row per actual batch of a product currently in storage. The domain
  model carries denormalized product/unit/location display fields (not just their ids), populated via an explicit JOIN
  on list and DAO relation traversal on single-row reads — see `PantryEntryRepositoryI`.

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
7. Editing a `pantry_entries` row (`PATCH`) is a full-state replace, not a sparse delta — the client always resends
   every editable field, including ones it isn't changing. `storageLocationId: null` unambiguously means "no location";
   there's no separate "field omitted" case (kotlinx.serialization can't tell "absent" from "sent as null" for a
   nullable field anyway). `quantityAmount <= 0` deletes the row (decision 2) instead of erroring.
8. `PantryEntryServiceI` depends on `ProductService` (not `ProductRepository`) but on
   `StorageLocationRepository`/`QuantityUnitRepository` directly (not their services) — inconsistent-looking on purpose.
   `storageLocationId`/`unitId` checks are pure existence/visibility reads with nothing else layered on top in either
   feature, so a service would only add indirection. Products are different: creating one on the fly (decision 9) means
   going through real dedup/trim logic, and once one path needs the service, the read path (`findVisibleById`) uses it
   too rather than splitting product access across two dependencies for the one feature.
9. Creating a pantry entry can create its product inline in the same call — `POST /pantry-entries` accepts either
   `productId` (existing, visible product) or `newProductName` (create a private product first). This is the "typed a
   name, autocomplete found nothing, add it anyway" flow. Both the product creation and the entry creation happen in one
   transaction (`PantryEntryServiceI.createEntry` calls `ProductService.createPrivateProduct`, which opens its own
   nested `tx.transactional { }` — Exposed joins the already-open outer transaction on the same coroutine rather than
   starting a second one, so a failure anywhere rolls back both) — no orphan product if the entry step fails. A
   `newProductName` colliding with a visible product (decision 4) fails the whole request with
   `PRODUCT_NAME_ALREADY_EXISTS`; it is never silently resolved to the existing product.
10. `GET /products/mine` lists all of the caller's own private products (no query needed) — for a "manage my products,
    rename them" screen. Deliberately separate from `GET /products/search`, which is autocomplete-shaped (requires a
    query, returns global + private together) and would be the wrong tool for a plain listing. Renaming a product
    (`PATCH /products/{id}`) needs no pantry-entry-side update at all: `pantry_entries.product_id` is a foreign key and
    `PantryEntry.productName` is always re-joined at read time, never cached on the row.
11. `GET /pantry-entries` is cursor-paginated (`PantryEntryRepositoryI.findPageByUserId`), not capped. An earlier cut of
    this endpoint had a hard `LIMIT 500` with nothing to fetch past it — silently dropping entries directly contradicts
    the app's entire purpose (decision 3's whole point is that the *current* pantry stays visible), so it was replaced
    rather than tuned. `limit` is a page size (default 50, max 200), never a total ceiling — the client always reaches
    everything, one page at a time. The cursor is deliberately trivial: rows are returned in insertion order (`id ASC`),
    and `afterId` is the plain integer id to resume after — no compound sort key, no opaque encoding. A first pass
    over-built this as a 3-column keyset (product name, expiration date, id) with hand-rolled `NULLS LAST`-safe boolean
    logic and a base64'd opaque cursor; that was the wrong shape for what this endpoint needs (see the next section) and
    was torn back out. If a future requirement needs a different resume order, revisit then — don't pre-build it now.
12. All four pantry-feature Exposed tables (`ProductsTable`, `StorageLocationsTable`, `QuantityUnitsTable`,
    `PantryEntriesTable`) live in `feature/product/data/database/`, not split one-per-feature the way `domain/`,
    `service/`, and `routing/` are. `storageLocation`, `quantityUnit`, and `pantryEntry` reach across into
    `com.shelflife.feature.product.data.database` for their own table/entity definitions. This is a deliberate exception
    to the feature-based layout (see ARCHITECTURE.md), not an oversight: `PantryEntriesTable` has FK relations into all
    three of the others, and Exposed's `IntEntity`/`referencedOn` DAO relations are simplest to define when the related
    tables live in one file/package. Nothing prevents splitting them later (single Gradle module, no circular-dependency
    constraint) — it just hasn't been worth the churn yet.

## What the backend must get right

The backend's job here stops at persistence and correctness: save what the client sent, and hand back everything the
caller is entitled to see. Sorting, grouping, and any display-oriented formatting or calculation are frontend concerns —
the backend never re-derives them just because it *could*.

- Persistence: save what's asked, correctly (decisions 1–4, 7, 9).
- Visibility/search: global + the caller's private products together, and never leak another user's private data
  (decisions 4, 8, 10).
- Data completeness: pagination reaches every entry the caller owns, with no silent cap (decision 11).
- Everything else — sort order, grouping by product, `expiration_date` display logic, aggregation across entries — is
  the frontend's job. The backend returns raw fields (`unitCategory`, `unitMultiplier`, `expirationDate`, ...) in
  insertion order and lets the client do the math and the formatting.
