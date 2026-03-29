#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import plistlib
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path
import xml.etree.ElementTree as ET

APP_NAME = "Mod Builder BW"
MAC_APP_BUNDLE_NAME = "Mod Builder BW.app"
MAC_PKG_NAME = "ModBuilderBW-macOS.pkg"
MAC_DMG_NAME = "ModBuilderBW-macOS.dmg"
WIN_INSTALLER_NAME = "ModBuilderBW-Windows-Installer.exe"
MAC_BUNDLE_ID = "com.ewalab.modbuilderbw"
APP_VERSION = "1.0.0"
APP_AUTHOR = "Blackwot"
APP_LICENSE_LABEL = "Free to use"
DOCKER_NSIS_IMAGE = "ubuntu:24.04"
JAVAFX_MODULES = ["base", "graphics", "controls", "fxml", "swing"]
ADOPTIUM_API_LATEST_JRE21_WIN_X64 = (
    "https://api.adoptium.net/v3/assets/latest/21/hotspot"
    "?architecture=x64&image_type=jre&os=windows&heap_size=normal&jvm_impl=hotspot&release_type=ga"
)
NSIS_PORTABLE_WIN_ZIP_URL = "https://downloads.sourceforge.net/project/nsis/NSIS%203/3.10/nsis-3.10.zip"
DEFAULT_ICON_CANDIDATES = [
    Path("/Users/amarkovic/Downloads/xcode-s-dark-96x96_2x.png"),
]

_built_project = False


def run(cmd: list[str], cwd: Path | None = None, capture: bool = False) -> str:
    kwargs = {
        "cwd": str(cwd) if cwd else None,
        "text": True,
    }
    if capture:
        kwargs["stdout"] = subprocess.PIPE
        kwargs["stderr"] = subprocess.STDOUT
    proc = subprocess.run(cmd, check=True, **kwargs)
    return proc.stdout if capture else ""


def ensure_tool(tool: str, install_hint: str) -> None:
    if shutil.which(tool) is None:
        raise RuntimeError(f"Missing required tool: {tool}. {install_hint}")


def _curl_text(url: str) -> str:
    ensure_tool("curl", "Install curl.")
    return run(["curl", "-fsSL", url], capture=True)


def _curl_download(url: str, dest: Path) -> None:
    ensure_tool("curl", "Install curl.")
    dest.parent.mkdir(parents=True, exist_ok=True)
    run(["curl", "-fL", url, "-o", str(dest)])


def ensure_docker_ready() -> None:
    ensure_tool("docker", "Install Docker Desktop or OrbStack and keep daemon running.")
    try:
        subprocess.run(
            ["docker", "info"],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
    except Exception as exc:
        raise RuntimeError("Docker daemon is not running. Start Docker/OrbStack and retry.") from exc


def _pom_info(project_root: Path) -> tuple[str, str]:
    pom = project_root / "pom.xml"
    tree = ET.parse(pom)
    root = tree.getroot()
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}

    version_node = root.find("m:version", ns)
    if version_node is None or not (version_node.text or "").strip():
        raise RuntimeError("Could not read project version from pom.xml")
    project_version = version_node.text.strip()

    javafx_version_node = root.find("m:properties/m:javafx.version", ns)
    if javafx_version_node is None or not (javafx_version_node.text or "").strip():
        raise RuntimeError("Could not read javafx.version from pom.xml")
    javafx_version = javafx_version_node.text.strip()

    return project_version, javafx_version


def build_project(project_root: Path) -> None:
    global _built_project
    if _built_project:
        return
    mvnw = project_root / "mvnw"
    if not mvnw.exists():
        raise FileNotFoundError(f"Missing Maven wrapper: {mvnw}")
    print("[build] Maven package + JavaFX jlink...")
    run([str(mvnw), "-q", "-DskipTests", "package", "javafx:jlink"], cwd=project_root)
    _built_project = True


def _copy_if_exists(src: Path, dst: Path) -> None:
    if not src.exists():
        return
    if src.is_dir():
        shutil.copytree(src, dst, dirs_exist_ok=True)
    else:
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)


def _resolve_icon_source(icon_arg: str | None) -> Path | None:
    if icon_arg:
        p = Path(icon_arg).expanduser().resolve()
        if not p.is_file():
            raise FileNotFoundError(f"Icon file not found: {p}")
        return p
    for candidate in DEFAULT_ICON_CANDIDATES:
        if candidate.is_file():
            return candidate
    return None


def _sips_to_png(src: Path, dest: Path) -> None:
    ensure_tool("sips", "Install macOS command line tools.")
    dest.parent.mkdir(parents=True, exist_ok=True)
    run(["sips", "-s", "format", "png", str(src), "--out", str(dest)])


def _png_dimensions(png_path: Path) -> tuple[int, int]:
    data = png_path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise RuntimeError(f"Not a PNG file: {png_path}")
    if data[12:16] != b"IHDR":
        raise RuntimeError(f"Invalid PNG (IHDR not found): {png_path}")
    w, h = struct.unpack(">II", data[16:24])
    return int(w), int(h)


def _write_png_as_ico(png_path: Path, ico_path: Path) -> None:
    png_bytes = png_path.read_bytes()
    width, height = _png_dimensions(png_path)
    if width > 256 or height > 256:
        with tempfile.TemporaryDirectory(prefix="ico_resize_") as tmp_name:
            tmp_png = Path(tmp_name) / "icon_256.png"
            run(["sips", "-z", "256", "256", str(png_path), "--out", str(tmp_png)])
            return _write_png_as_ico(tmp_png, ico_path)

    w_byte = 0 if width == 256 else width
    h_byte = 0 if height == 256 else height
    header = struct.pack("<HHH", 0, 1, 1)
    entry = struct.pack(
        "<BBBBHHII",
        w_byte,
        h_byte,
        0,
        0,
        1,
        32,
        len(png_bytes),
        6 + 16,
    )
    ico_path.parent.mkdir(parents=True, exist_ok=True)
    ico_path.write_bytes(header + entry + png_bytes)


def _ensure_ico(source_icon: Path, target_ico: Path) -> None:
    suffix = source_icon.suffix.lower()
    if suffix == ".ico":
        shutil.copy2(source_icon, target_ico)
        return
    with tempfile.TemporaryDirectory(prefix="icon_to_ico_") as tmp_name:
        tmp_dir = Path(tmp_name)
        tmp_png = tmp_dir / "icon.png"
        if suffix == ".png":
            shutil.copy2(source_icon, tmp_png)
        else:
            _sips_to_png(source_icon, tmp_png)
        _write_png_as_ico(tmp_png, target_ico)


def _ensure_icns(source_icon: Path, target_icns: Path) -> None:
    suffix = source_icon.suffix.lower()
    if suffix == ".icns":
        shutil.copy2(source_icon, target_icns)
        return

    ensure_tool("sips", "Install macOS command line tools.")
    ensure_tool("iconutil", "Install macOS command line tools.")

    with tempfile.TemporaryDirectory(prefix="icon_to_icns_") as tmp_name:
        tmp_dir = Path(tmp_name)
        iconset = tmp_dir / "AppIcon.iconset"
        iconset.mkdir(parents=True, exist_ok=True)

        base_png = tmp_dir / "base.png"
        if suffix == ".png":
            shutil.copy2(source_icon, base_png)
        else:
            _sips_to_png(source_icon, base_png)

        sizes = [
            (16, "icon_16x16.png"),
            (32, "icon_16x16@2x.png"),
            (32, "icon_32x32.png"),
            (64, "icon_32x32@2x.png"),
            (128, "icon_128x128.png"),
            (256, "icon_128x128@2x.png"),
            (256, "icon_256x256.png"),
            (512, "icon_256x256@2x.png"),
            (512, "icon_512x512.png"),
            (1024, "icon_512x512@2x.png"),
        ]
        for px, name in sizes:
            out = iconset / name
            run(["sips", "-z", str(px), str(px), str(base_png), "--out", str(out)])

        target_icns.parent.mkdir(parents=True, exist_ok=True)
        run(["iconutil", "-c", "icns", str(iconset), "-o", str(target_icns)])


def _build_macos_app_bundle(project_root: Path, out_dir: Path, icon_source: Path | None) -> Path:
    build_project(project_root)
    jlink_image = project_root / "target" / "ModBuilder"
    if not jlink_image.is_dir():
        raise RuntimeError(f"JavaFX jlink image not found: {jlink_image}")

    app_bundle = out_dir / MAC_APP_BUNDLE_NAME
    if app_bundle.exists():
        if app_bundle.is_dir():
            shutil.rmtree(app_bundle)
        else:
            app_bundle.unlink()

    contents = app_bundle / "Contents"
    macos_dir = contents / "MacOS"
    resources_dir = contents / "Resources"
    runtime_dir = resources_dir / "runtime"
    macos_dir.mkdir(parents=True, exist_ok=True)
    resources_dir.mkdir(parents=True, exist_ok=True)

    shutil.copytree(jlink_image, runtime_dir, dirs_exist_ok=True)

    launcher_name = "ModBuilderBW"
    launcher = macos_dir / launcher_name
    launcher.write_text(
        "#!/bin/sh\n"
        "DIR=\"$(cd \"$(dirname \"$0\")\" && pwd)\"\n"
        "exec \"$DIR/../Resources/runtime/bin/java\" --enable-native-access=javafx.graphics -m com.ewalab.modbillder/com.ewalab.modbillder.Launcher \"$@\"\n",
        encoding="utf-8",
    )
    launcher.chmod(0o755)

    icon_file_name = None
    if icon_source is not None:
        icon_file_name = "AppIcon.icns"
        _ensure_icns(icon_source, resources_dir / icon_file_name)

    plist = {
        "CFBundleName": APP_NAME,
        "CFBundleDisplayName": APP_NAME,
        "CFBundleIdentifier": MAC_BUNDLE_ID,
        "CFBundleVersion": APP_VERSION,
        "CFBundleShortVersionString": APP_VERSION,
        "CFBundlePackageType": "APPL",
        "CFBundleExecutable": launcher_name,
        "LSMinimumSystemVersion": "11.0",
        "NSHighResolutionCapable": True,
        "LSApplicationCategoryType": "public.app-category.utilities",
        "CFBundleGetInfoString": f"{APP_NAME} | Author: {APP_AUTHOR} | {APP_LICENSE_LABEL}",
        "NSHumanReadableCopyright": f"Author: {APP_AUTHOR} | {APP_LICENSE_LABEL}",
    }
    if icon_file_name:
        plist["CFBundleIconFile"] = icon_file_name

    with (contents / "Info.plist").open("wb") as f:
        plistlib.dump(plist, f, sort_keys=False)

    (contents / "PkgInfo").write_text("APPL????", encoding="ascii")

    return app_bundle


def _build_macos_dmg(app_bundle: Path, out_dir: Path) -> Path:
    ensure_tool("hdiutil", "hdiutil is required on macOS.")
    dmg_path = out_dir / MAC_DMG_NAME
    if dmg_path.exists():
        dmg_path.unlink()

    with tempfile.TemporaryDirectory(prefix="modbuilder_bw_dmg_") as tmp_name:
        tmp_dir = Path(tmp_name)
        stage_dir = tmp_dir / "dmg_root"
        stage_dir.mkdir(parents=True, exist_ok=True)
        staged_app = stage_dir / app_bundle.name
        shutil.copytree(app_bundle, staged_app, dirs_exist_ok=True)
        apps_link = stage_dir / "Applications"
        if apps_link.exists() or apps_link.is_symlink():
            apps_link.unlink()
        apps_link.symlink_to(Path("/Applications"))

        # hdiutil sometimes underestimates temporary image size for large app bundles; provide headroom.
        total_bytes = 0
        for path in stage_dir.rglob("*"):
            if path.is_symlink():
                continue
            if path.is_file():
                total_bytes += path.stat().st_size
        size_mb = max(256, int(total_bytes / (1024 * 1024) * 2.2) + 256)

        print(f"[macOS] Building DMG... (size {size_mb}m)")
        run([
            "hdiutil", "create",
            "-size", f"{size_mb}m",
            "-fs", "HFS+",
            "-volname", APP_NAME,
            "-srcfolder", str(stage_dir),
            "-format", "UDZO",
            "-ov",
            str(dmg_path),
        ])

    if not dmg_path.exists():
        raise RuntimeError(f"macOS DMG was not produced: {dmg_path}")
    return dmg_path


def build_macos_installer(project_root: Path, out_dir: Path, icon_source: Path | None) -> tuple[Path, Path, Path]:
    if sys.platform != "darwin":
        raise RuntimeError("macOS installer build can only run on macOS.")

    ensure_tool("pkgbuild", "Install Xcode Command Line Tools.")
    app_bundle = _build_macos_app_bundle(project_root, out_dir, icon_source)

    with tempfile.TemporaryDirectory(prefix="modbuilder_bw_mac_pkg_") as tmp_name:
        tmp_dir = Path(tmp_name)
        payload_root = tmp_dir / "payload_root"
        apps_dir = payload_root / "Applications"
        apps_dir.mkdir(parents=True, exist_ok=True)
        shutil.copytree(app_bundle, apps_dir / app_bundle.name, dirs_exist_ok=True)

        pkg_path = out_dir / MAC_PKG_NAME
        if pkg_path.exists():
            pkg_path.unlink()

        print("[macOS] Building PKG (installs .app into /Applications)...")
        run(
            [
                "pkgbuild",
                "--root",
                str(payload_root),
                "--identifier",
                MAC_BUNDLE_ID,
                "--version",
                APP_VERSION,
                "--install-location",
                "/",
                str(pkg_path),
            ]
        )

    if not pkg_path.exists():
        raise RuntimeError(f"macOS PKG was not produced: {pkg_path}")

    dmg_path = _build_macos_dmg(app_bundle, out_dir)
    return app_bundle, pkg_path, dmg_path


def _resolve_latest_adoptium_win_jre() -> tuple[str, str]:
    data = json.loads(_curl_text(ADOPTIUM_API_LATEST_JRE21_WIN_X64))
    if not isinstance(data, list) or not data:
        raise RuntimeError("Adoptium API returned no JRE releases for Windows x64.")

    for item in data:
        pkg = ((item or {}).get("binary") or {}).get("package") or {}
        link = pkg.get("link")
        name = pkg.get("name")
        if isinstance(link, str) and isinstance(name, str) and name.lower().endswith(".zip"):
            return link, name
    raise RuntimeError("Could not find ZIP package in Adoptium API response.")


def _download_windows_jre21_portable(runtime_dir: Path) -> None:
    link, filename = _resolve_latest_adoptium_win_jre()
    with tempfile.TemporaryDirectory(prefix="adoptium_jre_win_") as tmp_name:
        tmp_dir = Path(tmp_name)
        zip_path = tmp_dir / filename
        extract_dir = tmp_dir / "extract"
        print(f"[Windows] Downloading bundled JRE: {filename}")
        _curl_download(link, zip_path)
        with zipfile.ZipFile(zip_path) as zf:
            zf.extractall(extract_dir)

        top_dirs = [p for p in extract_dir.iterdir() if p.is_dir()]
        if len(top_dirs) != 1:
            raise RuntimeError("Unexpected JRE ZIP layout from Adoptium.")
        extracted_root = top_dirs[0]

        if runtime_dir.exists():
            shutil.rmtree(runtime_dir)
        runtime_dir.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(extracted_root, runtime_dir)

        java_exe = runtime_dir / "bin" / "java.exe"
        if not java_exe.exists():
            raise RuntimeError(f"Bundled JRE missing java.exe: {java_exe}")


def _download_windows_nsis_portable(nsis_dir: Path) -> None:
    with tempfile.TemporaryDirectory(prefix="nsis_win_") as tmp_name:
        tmp_dir = Path(tmp_name)
        zip_path = tmp_dir / "nsis.zip"
        extract_dir = tmp_dir / "extract"
        print("[Windows] Downloading bundled NSIS (portable)...")
        _curl_download(NSIS_PORTABLE_WIN_ZIP_URL, zip_path)
        with zipfile.ZipFile(zip_path) as zf:
            zf.extractall(extract_dir)

        top_dirs = [p for p in extract_dir.iterdir() if p.is_dir()]
        if len(top_dirs) != 1:
            raise RuntimeError("Unexpected NSIS ZIP layout.")
        extracted_root = top_dirs[0]

        if nsis_dir.exists():
            shutil.rmtree(nsis_dir)
        nsis_dir.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(extracted_root, nsis_dir)

        makensis = nsis_dir / "makensis.exe"
        if not makensis.exists():
            raise RuntimeError(f"Bundled NSIS missing makensis.exe: {makensis}")


def _ensure_javafx_windows_jars(project_root: Path, javafx_version: str, dest_lib_dir: Path) -> None:
    mvnw = project_root / "mvnw"
    if not mvnw.exists():
        raise FileNotFoundError(f"Missing Maven wrapper: {mvnw}")

    dest_lib_dir.mkdir(parents=True, exist_ok=True)
    local_repo = Path.home() / ".m2" / "repository" / "org" / "openjfx"

    for mod in JAVAFX_MODULES:
        artifact = f"org.openjfx:javafx-{mod}:{javafx_version}"
        artifact_win = f"org.openjfx:javafx-{mod}:{javafx_version}:jar:win"
        print(f"[Windows] Resolving JavaFX module: javafx-{mod} ({javafx_version})")
        run([str(mvnw), "-q", "dependency:get", f"-Dartifact={artifact}"], cwd=project_root)
        run([str(mvnw), "-q", "dependency:get", f"-Dartifact={artifact_win}"], cwd=project_root)

        mod_repo = local_repo / f"javafx-{mod}" / javafx_version
        generic_jar = mod_repo / f"javafx-{mod}-{javafx_version}.jar"
        win_jar = mod_repo / f"javafx-{mod}-{javafx_version}-win.jar"
        if not generic_jar.exists() or not win_jar.exists():
            raise RuntimeError(f"Missing resolved JavaFX jars for module javafx-{mod} in {mod_repo}")
        shutil.copy2(generic_jar, dest_lib_dir / generic_jar.name)
        shutil.copy2(win_jar, dest_lib_dir / win_jar.name)


def _locate_app_jar(project_root: Path, project_version: str) -> Path:
    candidates = [
        project_root / "target" / f"ModBillder-{project_version}.jar",
        project_root / "target" / f"ModBuilder-{project_version}.jar",
    ]
    for c in candidates:
        if c.exists():
            return c
    jars = sorted((project_root / "target").glob("*.jar"))
    if not jars:
        raise RuntimeError("No built application jar found under target/.")
    return jars[0]


def build_windows_installer(project_root: Path, out_dir: Path, icon_source: Path | None) -> Path:
    ensure_docker_ready()
    build_project(project_root)
    project_version, javafx_version = _pom_info(project_root)

    with tempfile.TemporaryDirectory(prefix="modbuilder_bw_win_inst_") as tmp_name:
        tmp_dir = Path(tmp_name)
        package_dir = tmp_dir / "package"
        app_dir = package_dir / "app"
        lib_dir = app_dir / "lib"
        runtime_dir = package_dir / "runtime"
        package_dir.mkdir(parents=True, exist_ok=True)

        app_jar_src = _locate_app_jar(project_root, project_version)
        app_jar_dst = app_dir / "ModBuilderBW.jar"
        app_dir.mkdir(parents=True, exist_ok=True)
        shutil.copy2(app_jar_src, app_jar_dst)

        _ensure_javafx_windows_jars(project_root, javafx_version, lib_dir)
        _download_windows_jre21_portable(runtime_dir)
        _download_windows_nsis_portable(package_dir / "tools" / "nsis")

        (package_dir / "README_FIRST.txt").write_text(
            "Mod Builder BW (Windows Standalone)\r\n"
            f"Author: {APP_AUTHOR}\r\n"
            f"License: {APP_LICENSE_LABEL}\r\n"
            "\r\n"
            "This installer includes a bundled Java Runtime (JRE 21).\r\n"
            "No separate Java installation is required.\r\n"
            "NSIS (makensis) is also bundled for creating Windows mod installers.\r\n"
            "\r\n"
            "Run:\r\n"
            "- Start Menu shortcut 'Mod Builder BW'\r\n"
            "or\r\n"
            "- run_mod_builder.vbs (recommended, no console window)\r\n"
            "- run_mod_builder.bat (debug launcher)\r\n",
            encoding="utf-8",
        )

        (package_dir / "run_mod_builder.bat").write_text(
            "@echo off\r\n"
            "setlocal\r\n"
            "set \"BASE=%~dp0\"\r\n"
            "cd /d \"%BASE%\"\r\n"
            "if not exist \"%BASE%runtime\\bin\\java.exe\" (\r\n"
            "  echo Bundled Java runtime is missing: %BASE%runtime\\bin\\java.exe\r\n"
            "  pause\r\n"
            "  exit /b 1\r\n"
            ")\r\n"
            "set \"JAVA_OPTS=-Dprism.allowhidpi=true -Dsun.java2d.dpiaware=true -Dsun.java2d.uiScale.enabled=true -Dglass.win.minHiDPI=1.0\"\r\n"
            "\"%BASE%runtime\\bin\\java.exe\" %JAVA_OPTS% --module-path \"%BASE%app;%BASE%app\\lib\" --module com.ewalab.modbillder/com.ewalab.modbillder.Launcher\r\n"
            "set EXITCODE=%errorlevel%\r\n"
            "if %EXITCODE% neq 0 (\r\n"
            "  echo.\r\n"
            "  echo Mod Builder failed to start.\r\n"
            "  pause\r\n"
            ")\r\n"
            "exit /b %EXITCODE%\r\n",
            encoding="utf-8",
        )


        (package_dir / "run_mod_builder.vbs").write_text(
            "\r\n".join(
                [
                    "Option Explicit",
                    "Dim fso, shell, baseDir, batchPath, cmd",
                    'Set fso = CreateObject("Scripting.FileSystemObject")',
                    'Set shell = CreateObject("WScript.Shell")',
                    "baseDir = fso.GetParentFolderName(WScript.ScriptFullName)",
                    'batchPath = baseDir & "\\run_mod_builder.bat"',
                    "If Not fso.FileExists(batchPath) Then",
                    f'  MsgBox "Launcher file is missing:" & vbCrLf & batchPath, vbCritical, "{APP_NAME}"',
                    "  WScript.Quit 1",
                    "End If",
                    "shell.CurrentDirectory = baseDir",
                    'cmd = "cmd.exe /c " & Chr(34) & Chr(34) & batchPath & Chr(34) & Chr(34)',
                    "shell.Run cmd, 0, False",
                    "WScript.Quit 0",
                ]
            ) + "\r\n",
            encoding="utf-8",
        )

        icon_clause = ""
        shortcut_icon_clause = ""
        if icon_source is not None:
            ico_path = tmp_dir / "installer_icon.ico"
            _ensure_ico(icon_source, ico_path)
            icon_clause = '!define MUI_ICON "installer_icon.ico"\n!define MUI_UNICON "installer_icon.ico"\nIcon "installer_icon.ico"\n'
            if ico_path.exists():
                shutil.copy2(ico_path, package_dir / "app_icon.ico")
                shortcut_icon_clause = ' "$INSTDIR\\app_icon.ico" 0'

        nsi_path = tmp_dir / "installer.nsi"
        nsi_path.write_text(
            '!include "MUI2.nsh"\n'
            f'Name "{APP_NAME}"\n'
            f'OutFile "/out/{WIN_INSTALLER_NAME}"\n'
            'InstallDir "$LOCALAPPDATA\\Mod Builder BW"\n'
            f'{icon_clause}'
            'ManifestDPIAware true\n'
            'RequestExecutionLevel user\n'
            '!define MUI_ABORTWARNING\n'
            '!insertmacro MUI_PAGE_WELCOME\n'
            '!insertmacro MUI_PAGE_DIRECTORY\n'
            '!insertmacro MUI_PAGE_INSTFILES\n'
            '!insertmacro MUI_PAGE_FINISH\n'
            '!insertmacro MUI_LANGUAGE "English"\n'
            'Section "Install"\n'
            '  SetOutPath "$INSTDIR"\n'
            '  File /r "package\\*"\n'
            '  CreateDirectory "$SMPROGRAMS\\Mod Builder BW"\n'
            f'  CreateShortCut "$SMPROGRAMS\\Mod Builder BW\\Mod Builder BW.lnk" "$INSTDIR\\run_mod_builder.vbs" ""{shortcut_icon_clause}\n'
            f'  CreateShortCut "$DESKTOP\\Mod Builder BW.lnk" "$INSTDIR\\run_mod_builder.vbs" ""{shortcut_icon_clause}\n'
            '  WriteUninstaller "$INSTDIR\\Uninstall.exe"\n'
            'SectionEnd\n'
            'Section "Uninstall"\n'
            '  Delete "$DESKTOP\\Mod Builder BW.lnk"\n'
            '  Delete "$SMPROGRAMS\\Mod Builder BW\\Mod Builder BW.lnk"\n'
            '  RMDir "$SMPROGRAMS\\Mod Builder BW"\n'
            '  Delete "$INSTDIR\\Uninstall.exe"\n'
            '  RMDir /r "$INSTDIR"\n'
            'SectionEnd\n',
            encoding="utf-8",
        )

        print("[Windows] Building EXE installer via Docker+NSIS (standalone runtime bundled)...")
        run(
            [
                "docker",
                "run",
                "--rm",
                "-v",
                f"{tmp_dir}:/work",
                "-v",
                f"{out_dir}:/out",
                DOCKER_NSIS_IMAGE,
                "sh",
                "-lc",
                "apt-get update -qq && apt-get install -y -qq nsis >/dev/null && cd /work && makensis installer.nsi",
            ]
        )

    installer_path = out_dir / WIN_INSTALLER_NAME
    if not installer_path.exists():
        raise RuntimeError(f"Windows installer was not produced: {installer_path}")
    return installer_path


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Build Mod Builder BW installers for macOS and Windows.")
    parser.add_argument(
        "--output",
        type=Path,
        default=Path.home() / "Desktop" / "Mod Builder" / "installer_outputs_bw",
        help="Output folder for produced installers",
    )
    parser.add_argument(
        "--target",
        choices=["all", "mac", "windows"],
        default="all",
        help="Which installers to build",
    )
    parser.add_argument(
        "--icon",
        default=None,
        help="Icon file for macOS app and Windows installer (.png/.icns/.ico). Default: auto-detect from Downloads.",
    )
    args = parser.parse_args(argv)

    project_root = Path(__file__).resolve().parent
    out_dir = args.output.expanduser().resolve()
    out_dir.mkdir(parents=True, exist_ok=True)
    icon_source = _resolve_icon_source(args.icon)
    if icon_source is not None:
        print(f"Using icon: {icon_source}")
    else:
        print("No icon selected (build will use default platform icons).")

    built: list[str] = []

    if args.target in {"all", "mac"}:
        try:
            app_bundle, pkg, dmg = build_macos_installer(project_root, out_dir, icon_source)
            built.append(f"macOS APP: {app_bundle}")
            built.append(f"macOS PKG: {pkg}")
            built.append(f"macOS DMG: {dmg}")
        except Exception as exc:
            print(f"[macOS] build failed: {exc}", file=sys.stderr)
            if args.target == "mac":
                return 1

    if args.target in {"all", "windows"}:
        try:
            exe = build_windows_installer(project_root, out_dir, icon_source)
            built.append(f"Windows EXE installer (standalone): {exe}")
        except Exception as exc:
            print(f"[Windows] build failed: {exc}", file=sys.stderr)
            if args.target == "windows":
                return 1

    if not built:
        print("No installers were built.", file=sys.stderr)
        return 1

    print("Installer build complete:")
    for line in built:
        print(f"- {line}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
