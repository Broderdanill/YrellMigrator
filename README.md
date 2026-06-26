# Yrell Migrator v0.86.0

Eclipse/BMC Developer Studio plugin for comparing, caching and migrating AR System definitions, supported catalog objects and form data between connected environments.

## Highlights in v0.86.0

- Added a dedicated **Yrell Migrator** Developer Studio perspective.
  - The perspective hides the normal editor area and opens the migrator as the main workspace.
  - The existing **Yrell Migrator > Open Yrell Migrator** menu command now tries to switch to this perspective first and falls back to opening the view in the current perspective if the workbench refuses the switch.
- Added before-state migration backups.
  - Migration Pack runs now require/select a `.ymbackup` file before target writes start.
  - Normal object migrations also create a `.ymbackup` before the shared migration executor runs.
  - Backups store target-side definitions as `.def` files and target-side entry data as CSV.
  - If an object or row did not exist before the migration, the backup records a delete-on-restore instruction.
  - New **Restore backup...** action can restore a `.ymbackup` into connected target environment(s).
- Changed new Migration Pack exports to a more open ZIP package format.
  - Default export extension is now `.zip`.
  - Definition payloads are stored under `definitions/` as `.def` files.
  - Form/catalog data payloads are stored under `data/` as CSV files.
  - `manifest.xml`, `preview.txt` and `checksums.sha256` remain in the ZIP.
  - Existing `.ympack` and legacy `.hlxpack` imports are still supported.
- Previous v0.85.0 rename remains:
  - Product name: **Yrell Migrator**.
  - Bundle/package id: `se.yrell.migrator`.
  - Config file: `yrell-migrator.properties`, with legacy fallback for `helix-migrator.properties`.
- Previous v0.81.0+ behavior remains: entry-data imports bypass required-field and pattern/menu validation during AR merge.

## Installation

Copy `install/se.yrell.migrator_0.86.0.jar` to the Developer Studio `x64/plugins` directory, remove older `se.yrell.migrator_*.jar` and `se.yrell.helix.migrator_*.jar` files, then start Developer Studio once with `-clean`.
