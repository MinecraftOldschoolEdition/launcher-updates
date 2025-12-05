### Mod Updater (CLI + GUI)

Cross‑platform updater for Prism Launcher / MultiMC instances or simple batch launches.

- CLI: Fetches the latest GitHub Release asset matching a regex and updates either the `mods/` jar or the legacy client jar, with backups.
- GUI: Classic‑style prompt (Yes/Not now) before updating. It also extracts `assets/` (or `resources/assets/`) from the downloaded mod jar into the instance’s `resources/assets`.

#### Build
- Prerequisite: JDK 8+ in `PATH`.
- From this folder:

```bash
javac -encoding UTF-8 -d out src/ModUpdater.java src/ModUpdaterGUI.java
jar cfe mod-updater.jar ModUpdater -C out .
jar cfe mod-updater-gui.jar ModUpdaterGUI -C out .
```

The resulting `mod-updater.jar` will be in this directory.

#### Usage
CLI general form:

```bash
java -jar mod-updater.jar \
  --repo "Org/Repo" \
  --assetRegex "YourMod-.*\\.jar" \
  --mode mods|clientJar|jarmods \
  --minecraftDir "/path/to/.minecraft" \
  [--clientJarPath "/path/to/bin/minecraft.jar"] \
  [--yes] [--dryRun]
```

- `--repo`: GitHub `owner/repo`.
- `--assetRegex`: Java regex for the asset filename to download.
- `--mode`: `mods` (default) or `clientJar`.
- `--minecraftDir`: Path to the instance’s `.minecraft` folder. If omitted, the tool will try `MC_DIR` env var or `--instanceDir` + `/.minecraft`.
- `--clientJarPath`: Only for `clientJar` mode; defaults to `<minecraftDir>/bin/minecraft.jar` if present.
- `--yes`: Non‑interactive (auto‑confirm update).
- `--dryRun`: Print actions without making changes.
- Optional env: `GITHUB_TOKEN` for higher GitHub API rate limits.

Examples:

```bash
# Update a mod jar in mods/
java -jar mod-updater.jar --repo "YourOrg/YourRepo" \
  --assetRegex "YourMod-.*\\.jar" --mode mods \
  --minecraftDir "$MC_DIR" --yes

# Replace legacy client jar (backup created)
java -jar mod-updater.jar --repo "YourOrg/YourRepo" \
  --assetRegex "Client-.*\\.jar" --mode clientJar \
  --minecraftDir "$MC_DIR" --yes
```

Backups are written as `<original>.bak.<timestamp>`.

#### Prism Launcher / MultiMC integration

Both Prism and MultiMC expose instance variables like `$INST_DIR` and `$MC_DIR` to custom commands. Use the following wiring options.

— Option A: Self‑updating instance (Pre‑Launch command)
1) Place `mod-updater.jar` under the instance folder, e.g. `"$INST_DIR/tools/mod-updater/mod-updater.jar"`.
2) Edit instance → Custom Commands → Pre‑Launch command:

```bash
$INST_JAVA -jar "$INST_DIR/tools/mod-updater/mod-updater.jar" \
  --repo "YourOrg/YourRepo" \
  --assetRegex "YourMod-.*\\.jar" \
  --mode mods \
  --minecraftDir "$MC_DIR" \
  --yes
```

- Enable “Wait until finished” and “Abort on error”. Works on Windows/macOS/Linux (Prism replaces variables appropriately). On Windows, `$INST_JAVA` resolves to the instance Java binary.

— Option B: Separate “Updater” instance managing another instance
1) Create a new empty instance named `Updater`.
2) Put `mod-updater.jar` in `"$INST_DIR/tools/mod-updater/mod-updater.jar"` of that updater instance.
3) Add a Custom Command to run the updater against a target instance (replace `ManagedInstanceFolder`):

```bash
$INST_JAVA -jar "$INST_DIR/tools/mod-updater/mod-updater.jar" \
  --repo "YourOrg/YourRepo" \
  --assetRegex "YourMod-.*\\.jar" \
  --mode mods \
  --instanceDir "/path/to/PrismLauncher/instances/ManagedInstanceFolder" \
  --yes
```

You can create a second custom command that launches the managed instance after the update.

#### Notes
- If you see GitHub API rate limits, set `GITHUB_TOKEN` in your environment before running.
- For strict environments, you can run without `--yes` to get a confirmation prompt.
- The tool does not modify launcher configuration; it only updates files under the instance’s game directory.

---

### GUI Updater (classic prompt + classic progress screen)

1) Configure `tools/mod-updater/updater.properties` with your `repo`, `jarRegex`, optional `assetsRegex`, and `minecraftDir`.
2) Build `mod-updater-gui.jar` as above.
3) Run it before the game starts:

```bash
java -jar tools/mod-updater/mod-updater-gui.jar --config tools/mod-updater/updater.properties
```

If an update is found, it prompts “New update available → Would you like to update?” and shows a textured progress screen (uses `assets/minecraft/textures/gui/menu/achievements/bg.png` when available). Then it:
- Installs the matched jar to:
  - `mods/` when `mode=mods`
  - `bin/minecraft.jar` when `mode=clientJar`
  - `jarmods/<jarmodName>` when `mode=jarmods` (instance root is resolved as the parent of `minecraftDir` if `--instanceDir` isn’t given)
- Extracts `assets/**` (and legacy `resources/**`) from the mod jar directly into the instance’s `minecraft/resources/assets`.

Example for a layout with `instanceRoot/jarmods/mod.jar` and `instanceRoot/minecraft/resources/assets`:

```properties
mode=jarmods
jarmodName=mod.jar
minecraftDir=/path/to/instanceRoot/minecraft
```

The updater will place the downloaded jar at `instanceRoot/jarmods/mod.jar` and copy assets under `instanceRoot/minecraft/resources/assets`.

### GitHub API rate limits and tokens

- The updater works anonymously; most users don’t need a token. Anonymous quota is ~60 requests per hour per IP, usually plenty.
- If you want higher limits without risking your account, do NOT ship your personal token. Options:
  - Ask users to set an environment variable `GITHUB_TOKEN` themselves. Tell them to create a Fine‑grained personal access token with:
    - Only “Public repositories (Read‑only)” permissions
    - Repository access restricted to your specific repo
    - No organization/admin scopes
  - Or host a small JSON manifest on GitHub Pages/CDN for version checks and use direct asset URLs for downloads.
- Never hard‑code a token in code or commit history. Prefer environment variables or launcher‑side variables to pass tokens if a user opts in.



