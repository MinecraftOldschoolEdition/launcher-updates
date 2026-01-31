#!/bin/zsh
# Build script for Mod Updater (macOS)
# Builds CLI, GUI, and launcher promoter versions

# Exit on error
set -e

# Get the directory where this script is located
SCRIPT_DIR="${0:A:h}"
cd "$SCRIPT_DIR"

echo "==================================="
echo "Building Mod Updater for macOS..."
echo "==================================="
echo ""

# Check for Java
if ! command -v javac &> /dev/null; then
    echo "Error: javac not found. Please install a JDK."
    exit 1
fi

if ! command -v jar &> /dev/null; then
    echo "Error: jar not found. Please install a JDK."
    exit 1
fi

# Show Java version
echo "Using Java:"
java -version 2>&1 | head -1
echo ""

# Clean output directory
echo "Cleaning output directory..."
rm -rf out
mkdir -p out

# Compile CLI updater (targeting Java 8 for Prism Launcher compatibility)
echo "Compiling ModUpdater.java..."
if ! javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -d out src/ModUpdater.java; then
    echo "Build failed: ModUpdater.java"
    exit 1
fi

# Compile GUI updater (targeting Java 8 for Prism Launcher compatibility)
echo "Compiling ModUpdaterGUI.java..."
if ! javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -d out src/ModUpdaterGUI.java src/LauncherBootstrap.java; then
    echo "Build failed: ModUpdaterGUI.java"
    exit 1
fi

# Compile launcher promoter helper
echo "Compiling LauncherUpdatePromoter.java..."
if ! javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -d out src/LauncherUpdatePromoter.java; then
    echo "Build failed: LauncherUpdatePromoter.java"
    exit 1
fi

# Copy bg.png resource to output (if needed by GUI)
if [[ -f src/bg.png ]]; then
    echo "Copying bg.png resource..."
    cp src/bg.png out/bg.png
fi

# Create CLI jar
echo "Creating mod-updater.jar..."
if ! jar cfe mod-updater.jar ModUpdater -C out .; then
    echo "Build failed: jar creation for CLI"
    exit 1
fi

# Create GUI jar
echo "Creating mod-updater-gui.jar..."
if ! jar cfe mod-updater-gui.jar LauncherBootstrap -C out .; then
    echo "Build failed: jar creation for GUI"
    exit 1
fi

# Create launcher promoter jar
echo "Creating launcher-promoter.jar..."
if ! jar cfe launcher-promoter.jar LauncherUpdatePromoter -C out .; then
    echo "Build failed: jar creation for launcher promoter"
    exit 1
fi

echo ""
echo "==================================="
echo "Build complete!"
echo "==================================="
echo "  - mod-updater.jar (CLI)"
echo "  - mod-updater-gui.jar (GUI)"
echo "  - launcher-promoter.jar (Post-exit helper)"
echo ""
ls -lh *.jar
