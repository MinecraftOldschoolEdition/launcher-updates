#!/bin/bash
# Build script for Mod Updater (Linux/Mac)
# Builds both CLI and GUI versions

set -e

echo "Building Mod Updater..."

# Clean output directory
rm -rf out
mkdir -p out

# Compile CLI updater
echo "Compiling ModUpdater.java..."
javac -encoding UTF-8 -d out src/ModUpdater.java

# Compile GUI updater
echo "Compiling ModUpdaterGUI.java..."
javac -encoding UTF-8 -d out src/ModUpdaterGUI.java

# Copy bg.png resource to output (if needed by GUI)
if [ -f src/bg.png ]; then
    cp src/bg.png out/bg.png
fi

# Create CLI jar
echo "Creating mod-updater.jar..."
jar cfe mod-updater.jar ModUpdater -C out .

# Create GUI jar
echo "Creating mod-updater-gui.jar..."
jar cfe mod-updater-gui.jar ModUpdaterGUI -C out .

echo ""
echo "Build complete!"
echo "  - mod-updater.jar (CLI)"
echo "  - mod-updater-gui.jar (GUI)"

