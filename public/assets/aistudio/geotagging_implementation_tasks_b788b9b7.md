# Geotagging & Path Interpolation Execution Tasks

* \[ \] **Phase 1: Timestamp Selection & Fallback Engine**

  * \[ \] Create `PhotoTimestampResolver` utility class.

  * \[ \] Implement priority chain: `DateTimeOriginal` -> earliest of (`DateTimeDigitized`, `DateTime`, etc.).

  * \[ \] Implement timezone offset resolution pairing to avoid hour-shift discrepancies.

  * \[ \] Return structured `ResolvedTimestamp(timeMs: Long, sourceTag: String)`.

  * \[ \] Add unit tests verifying missing tag fallbacks and timezone edge cases.

* \[ \] **Phase 2: Timeline Parser Refactoring (`GoogleTimelineParser.kt`)**

  * \[ \] Refactor point collection to maintain segment hierarchy rather than flattening all points.

  * \[ \] Implement distinct models: `TimelineVisit`, `TimelineActivitySegment`, `TimelinePathPoint`.

  * \[ \] Add `isEstimated: Boolean` flag to points distributed without explicit time offsets.

  * \[ \] Prevent point linkage across activity transitions or visit boundaries.

  * \[ \] Add unit tests for timeline JSON parsing with segmented journeys.

* \[ \] **Phase 3: Shared Settings & EXIF Safety Defaults**

  * \[ \] Locate existing default in compact `GeoTimelineCard` (toggle `writeToExif` from `true` to `false`).

  * \[ \] Locate `TimelineGeotagViewModel` and disable automatic EXIF write on GPS match.

  * \[ \] Migrate EXIF write preference and interpolation tolerance into centralized `GeotagPreferencesRepository` (backed by `DataStore`).

  * \[ \] Ensure default values:

    * `writeToExif`: `false`

    * `maxInterpolationGapMinutes`: `30`

    * `nearestPointToleranceMinutes`: `15`

    * `skipExistingGps`: `true`

* \[ \] **Phase 4: SQLite Database Additive Migration**

  * \[ \] Inspect existing `PhotoMapLocationCache` database helper / Room database.

  * \[ \] Replace any destructive `onUpgrade` (`DROP TABLE`) logic with an additive `ALTER TABLE` script.

  * \[ \] Add columns for match provenance: `source`, `match_method`, `resolved_timestamp`, `timestamp_source`, `sample_before_time`, `sample_after_time`, `sample_gap_minutes`, `is_estimated`, `exif_written`.

  * \[ \] Update data access objects (DAOs) and domain repository to write and read enriched provenance models.

  * \[ \] Write a migration verification test to ensure cached locations persist across schema update.

* \[ \] **Phase 5: Path Interpolation Engine Implementation**

  * \[ \] Create `TimelinePathInterpolator` service.

  * \[ \] Implement linear / geodesic interpolation bounded within identical segments.

  * \[ \] Enforce the max interpolation gap threshold check ($T_2 - T_1 \le T_{\text{max\_gap}}$).

  * \[ \] Add fallback ladder: Segment Path Interpolation -> `rawSignals.position` nearest search -> `visit` staypoint match.

  * \[ \] Unit test interpolation edge cases (boundary points, oversized gaps, stationary intervals).

* \[ \] **Phase 6: UI & Presentation Layer Integration**

  * \[ \] **Expanded Geo Timeline Card:**

    * \[ \] Add slider component bound to `maxInterpolationGapMinutes` (1–60 min range).

    * \[ \] Connect slider update events to trigger timeline re-match where applicable.

  * \[ \] **Photo Map Card:**

    * \[ \] Update selected media card to display match method badge (e.g., "Interpolated location"), sample gap, and data source.

  * \[ \] **Media Detail Viewer Drawer:**

    * \[ \] Update the Location section to read the shared provenance record from SQLite.

    * \[ \] Display fields: Location Source

* \[ \] **Phase 7: End-to-End Verification & Regression Testing**

  * \[ \] Verify original media files remain bit-for-bit unchanged when `writeToExif` is `OFF`.

  * \[ \] Verify geotags show consistently on both the Photo Map and Media Detail Viewer drawer.

  * \[ \] Test cross-boundary scenarios to ensure no interpolation happens across separate trips.