### Mod Updater (CLI + GUI)

Cross‑platform updater for Prism Launcher / MultiMC instances or simple batch launches.

- CLI: Fetches the latest GitHub Release asset matching a regex and updates either the `mods/` jar or the legacy client jar, with backups.
- GUI: Classic‑style prompt (Yes/Not now) before updating.
- GUI update path updates the patch jar when present, and can also install a companion LAN `server.jar`.
- GUI resource sync writes to `resources/assets` and supports incremental/full modes.
- Stable releases without a matching `patch.jar` are allowed; the launcher skips the client patch instead of aborting.
- When a release contains a matching `server.jar`, the launcher installs it to `minecraftDir/lan-server/server.jar`.
- The CLI supports the same companion server jar flow with `--serverAssetRegex`.
- Launcher self-updates are launch-blocking: the launcher installs its own update and restarts before any game update or resource sync can continue.

## Resource Sync Behavior

- Incremental sync (normal launch): periodically checks the selected resource-pack branch and persists its last successful commit/tree in `resources/.mcose-resource-sync.properties`.
- When that commit changes, only added or changed files are downloaded; files removed upstream are deleted only when the persisted manifest proves they belonged to the resource repository.
- Unchanged commits download no resources. Checks are throttled by `resourcePackCheckIntervalMinutes` (default: 60; set to `0` to check every launch).
- Choosing `Not now` for a game update skips the resource repository check and all resource downloads for that launch.
- Console output reports why resource downloads were skipped; when downloads run, it lists every queued and completed incremental file (and every file installed by a full refresh).
- Full sync (Force update + Yes): refreshes all resource files.
- Strict force semantics: if full sync fails, launcher aborts instead of launching with partial resources.
- Stable resource sync uses `resourcePackBranch`; beta updates use `resourcePackBetaBranch`.
- Full-sync mirror strategy: GitHub archive URL first, then codeload, then `master` fallback when the selected branch is `main`, then jsDelivr fallback.
- If the launcher jar is locked and the self-update can only be staged, the current game launch is stopped so stale launcher code cannot fetch assets.

## Config Keys

Add these keys to `tools/mod-updater/updater.properties` when needed:

- `resourcePackRepo=MinecraftOldschoolEdition/resourcepack`
- `resourcePackBranch=main`
- `resourcePackBetaBranch=beta`
- `resourcePackCheckIntervalMinutes=60`
- `serverJarRegex=server\.jar`

To use it in Prism Launcher use the following Custom Commands in your Minecraft instance:

**Pre-launch:**

"$INST_JAVA" -jar "$INST_DIR/tools/mod-updater/mod-updater-gui.jar" --config "$INST_DIR/tools/mod-updater/updater.properties" --instanceDir "$INST_DIR" --minecraftDir "$INST_MC_DIR"

**Post-exit:**

"$INST_JAVA" -jar "$INST_DIR/tools/mod-updater/launcher-promoter.jar" --instanceDir "$INST_DIR"

