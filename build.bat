@echo off
REM Build script for Mod Updater (Windows)
REM Builds both CLI and GUI versions

echo Building Mod Updater...

REM Clean output directory
if exist out rmdir /s /q out
mkdir out

REM Compile CLI updater
echo Compiling ModUpdater.java...
javac -encoding UTF-8 -d out src/ModUpdater.java
if errorlevel 1 (
    echo Build failed: ModUpdater.java
    exit /b 1
)

REM Compile GUI updater
echo Compiling ModUpdaterGUI.java...
javac -encoding UTF-8 -d out src/ModUpdaterGUI.java src/LauncherBootstrap.java
if errorlevel 1 (
    echo Build failed: ModUpdaterGUI.java
    exit /b 1
)

REM Compile launcher promoter helper
echo Compiling LauncherUpdatePromoter.java...
javac -encoding UTF-8 -d out src/LauncherUpdatePromoter.java
if errorlevel 1 (
    echo Build failed: LauncherUpdatePromoter.java
    exit /b 1
)

REM Copy bg.png resource to output (if needed by GUI)
if exist src/bg.png copy src/bg.png out\bg.png >nul 2>&1

REM Create CLI jar
echo Creating mod-updater.jar...
jar cfe mod-updater.jar ModUpdater -C out .
if errorlevel 1 (
    echo Build failed: jar creation for CLI
    exit /b 1
)

REM Create GUI jar
echo Creating mod-updater-gui.jar...
jar cfe mod-updater-gui.jar LauncherBootstrap -C out .
if errorlevel 1 (
    echo Build failed: jar creation for GUI
    exit /b 1
)

REM Create launcher promoter jar
echo Creating launcher-promoter.jar...
jar cfe launcher-promoter.jar LauncherUpdatePromoter -C out .
if errorlevel 1 (
    echo Build failed: jar creation for launcher promoter
    exit /b 1
)

echo.
echo Build complete!
echo   - mod-updater.jar (CLI)
echo   - mod-updater-gui.jar (GUI)
echo   - launcher-promoter.jar (Post-exit helper)

