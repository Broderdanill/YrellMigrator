# Yrell Migrator v0.85.0

Eclipse/BMC Developer Studio plugin for comparing, caching and migrating AR System definitions, supported catalog objects and form data between connected environments.

## Highlights in v0.85.0

- Renamed the product from **Helix Migrator** to **Yrell Migrator**.
- Changed the bundle/package id from `se.yrell.helix.migrator` to `se.yrell.migrator`.
- New portable Migration Pack file extension is `.ympack`; legacy `.hlxpack` files can still be imported.
- New shared config file name is `yrell-migrator.properties`; legacy `helix-migrator.properties` is still accepted.
- Previous preference values from the old bundle id are read as a fallback during upgrade.
- Previous v0.84.0 behavior remains: fixed the legacy pack import bug where ZIP payload bytes could be read twice, causing checksum failures or corrupt embedded payloads on import.
- Migration Pack now has stronger run-order support.
  - New **Phase** column shows the planned execution phase for every pack row.
  - New **Order for run** button sorts rows into a recommended migration order.
  - Run Pack and Run selected now automatically process rows by recommended execution phase even if the visual order is different.
  - Preflight and run reports include a planned run-order preview.
- Migration Pack usability improvements.
  - New **Rename...** button for naming packages before export.
  - New **Move up** / **Move down** buttons for manual visual ordering.
  - New **Clear status** button clears Last run state for selected rows, or all rows when nothing is selected.
- Previous v0.83.0 features remain:
  - Run selected, Last run status/message, and Save report.
  - More useful preflight with duplicate, target, zero-row, and missing-form-definition warnings.
- Previous v0.82.0 features remain:
  - ZIP-based `.ympack` files with `manifest.xml`, `payloads/`, `preview.txt`, `checksums.sha256` and `README.txt`.
  - Neutral XML field-value payloads for new form-data captures.
  - Backward-compatible import for older XML-based `.hlxpack` files and older serialized entry payloads.
  - Preflight and Retarget.
  - Embedded definition/data payloads so packs can run with only the target environment connected.
- Previous v0.81.0 merge behavior remains: entry-data imports bypass required-field and pattern/menu validation during AR merge.

## Installation

Copy `install/se.yrell.migrator_0.85.0.jar` to the Developer Studio `x64/plugins` directory, remove older `se.yrell.migrator_*.jar` and `se.yrell.helix.migrator_*.jar` files, then start Developer Studio once with `-clean`.
