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
- Closing the GUI instead of pressing Play exits the pre-launch command with a non-zero status, causing Prism Launcher to cancel that instance session.

## Resource Sync Behavior

- Incremental sync (normal launch): checks the selected resource-pack branch commit and persists its last successful commit/tree in `resources/.mcose-resource-sync.properties`.
- When that commit changes, only added or changed files are downloaded; files removed upstream are deleted only when the persisted manifest proves they belonged to the resource repository.
- The lightweight commit check runs on every normal launch, so new language or asset commits are detected immediately. When the commit is unchanged, `resourcePackCheckIntervalMinutes` throttles only the local missing-file integrity scan (default: 60; set to `0` to scan every launch).
- Choosing `Not now` for a game update skips the resource repository check and all resource downloads for that launch.
- Console output reports why resource downloads were skipped; when downloads run, it lists every queued and completed incremental file (and every file installed by a full refresh).
- Full sync (Force update + Yes): refreshes all resource files.
- Strict force semantics: if full sync fails, launcher aborts instead of launching with partial resources.
- Stable resource sync uses `resourcePackBranch`; beta updates use `resourcePackBetaBranch`.
- Full-sync mirror strategy: GitHub archive URL first, then codeload, then `master` fallback when the selected branch is `main`, then jsDelivr fallback.
- If the launcher jar is locked and the self-update can only be staged, the current game launch is stopped so stale launcher code cannot fetch assets.
- On Windows, the staged launcher update is preserved until `launcher-promoter.jar` replaces the unlocked GUI jar; a failed in-process promotion never deletes the pending update.

## Config Keys

Add these keys to `tools/mod-updater/updater.properties` when needed:

- `resourcePackRepo=MinecraftOldschoolEdition/resourcepack`
- `resourcePackBranch=main`
- `resourcePackBetaBranch=beta`
- `resourcePackCheckIntervalMinutes=60`
- `serverJarRegex=server\.jar`
- `launcherJarRegex=mod-updater-gui\.jar`
- `launcherPromoterJarRegex=launcher-promoter\.jar`

When the latest launcher release contains `launcher-promoter.jar`, the GUI tracks it as a separate component and installs it before updating or staging `mod-updater-gui.jar`. Releases without that asset continue to use the installed promoter.

To use it in Prism Launcher use the following Custom Commands in your Minecraft instance:

**Pre-launch:**

"$INST_JAVA" -jar "$INST_DIR/tools/mod-updater/mod-updater-gui.jar" --config "$INST_DIR/tools/mod-updater/updater.properties" --instanceDir "$INST_DIR" --minecraftDir "$INST_MC_DIR"

**Post-exit:**

"$INST_JAVA" -jar "$INST_DIR/tools/mod-updater/launcher-promoter.jar" --instanceDir "$INST_DIR"

The Post-exit command is required for launcher self-updates on Windows. If a staged-update message repeats, confirm that `launcher-promoter.jar` exists beside `mod-updater-gui.jar` and that the command above is configured for the instance.

