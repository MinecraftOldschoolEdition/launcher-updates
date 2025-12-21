#!/bin/bash
# Build script for Mod Updater (Linux/Mac)
# Builds both CLI and GUI versions

set -e

echo "Building Mod Updater..."

# Clean output directory
rm -rf out
mkdir -p out

# Compile CLI updater (targeting Java 8 for Prism Launcher compatibility)
echo "Compiling ModUpdater.java..."
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -d out src/ModUpdater.java

# Compile GUI updater (targeting Java 8 for Prism Launcher compatibility)
echo "Compiling ModUpdaterGUI.java..."
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -d out src/ModUpdaterGUI.java src/LauncherBootstrap.java

# Copy bg.png resource to output (if needed by GUI)
if [ -f src/bg.png ]; then
    cp src/bg.png out/bg.png
fi

# Create CLI jar
echo "Creating mod-updater.jar..."
jar cfe mod-updater.jar ModUpdater -C out .

# Create GUI jar
echo "Creating mod-updater-gui.jar..."
jar cfe mod-updater-gui.jar LauncherBootstrap -C out .

echo ""
echo "Build complete!"
echo "  - mod-updater.jar (CLI)"
echo "  - mod-updater-gui.jar (GUI)"

