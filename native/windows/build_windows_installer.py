#!/usr/bin/env python3
from __future__ import annotations

import argparse
import os
import shutil
import struct
import subprocess
import tempfile
import zipfile
from pathlib import Path

APP_NAME = "Mod Builder BW"
APP_EXE_NAME = "ModBuilderBW.Windows.exe"
INSTALLER_NAME = "ModBuilderBW-Windows-Native-Installer.exe"
APP_VERSION = "1.0.0"
PUBLISH_FOLDER_NAME = "ModBuilderBW-Windows-App"
APP_AUTHOR = "Blackwot"
APP_LICENSE_LABEL = "Free to use"
DOCKER_NSIS_IMAGE = "ubuntu:24.04"
PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_ICON = PROJECT_ROOT / "branding" / "ModBuilderBW.png"
NSIS_PORTABLE_WIN_ZIP_URL = "https://downloads.sourceforge.net/project/nsis/NSIS%203/3.10/nsis-3.10.zip"
IS_WINDOWS = os.name == "nt"


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


def curl_download(url: str, dest: Path) -> None:
    ensure_tool("curl", "Install curl.")
    dest.parent.mkdir(parents=True, exist_ok=True)
    run(["curl", "-fL", url, "-o", str(dest)])


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


def _render_square_png(source_png: Path, size: int, out_png: Path) -> None:
    with tempfile.TemporaryDirectory(prefix="ico_frame_") as tmp_name:
        tmp_dir = Path(tmp_name)
        scaled_png = tmp_dir / "scaled.png"
        run(["sips", "-Z", str(size), str(source_png), "--out", str(scaled_png)])
        width, height = _png_dimensions(scaled_png)
        if width == size and height == size:
            out_png.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(scaled_png, out_png)
            return

        run([
            "sips",
            "-p",
            str(size),
            str(size),
            str(scaled_png),
            "--padColor",
            "2B241F",
            "--out",
            str(out_png),
        ])


def _classic_ico_frame_from_png(frame_png: Path, size: int) -> bytes:
    with tempfile.TemporaryDirectory(prefix="ico_bmp_") as tmp_name:
        tmp_dir = Path(tmp_name)
        bmp_path = tmp_dir / f"icon_{size}.bmp"
        run(["sips", "-s", "format", "bmp", str(frame_png), "--out", str(bmp_path)])
        bmp = bmp_path.read_bytes()

    if bmp[:2] != b"BM":
        raise RuntimeError(f"Not a BMP file: {bmp_path}")

    pixel_offset = struct.unpack_from("<I", bmp, 10)[0]
    width = abs(struct.unpack_from("<i", bmp, 18)[0])
    height = abs(struct.unpack_from("<i", bmp, 22)[0])
    bits_per_pixel = struct.unpack_from("<H", bmp, 28)[0]
    if bits_per_pixel != 32:
        raise RuntimeError(f"Unsupported BMP bit depth for icon frame: {bits_per_pixel}")

    stride = ((width * bits_per_pixel + 31) // 32) * 4
    pixel_data = bmp[pixel_offset:pixel_offset + (stride * height)]
    rows = [pixel_data[row * stride:(row + 1) * stride] for row in range(height)]
    if struct.unpack_from("<i", bmp, 22)[0] < 0:
        rows = list(reversed(rows))

    xor_bitmap = b"".join(rows)
    mask_stride = ((width + 31) // 32) * 4
    and_mask = b"\x00" * (mask_stride * height)
    dib_header = struct.pack(
        "<IiiHHIIiiII",
        40,
        width,
        height * 2,
        1,
        32,
        0,
        len(xor_bitmap),
        0,
        0,
        0,
        0,
    )
    return dib_header + xor_bitmap + and_mask


def _write_png_as_ico(png_path: Path, ico_path: Path) -> None:
    frame_sizes = [16, 24, 32, 48, 64, 128, 256]
    frames: list[tuple[int, bytes]] = []
    with tempfile.TemporaryDirectory(prefix="ico_multi_") as tmp_name:
        tmp_dir = Path(tmp_name)
        for size in frame_sizes:
            frame_png = tmp_dir / f"icon_{size}.png"
            _render_square_png(png_path, size, frame_png)
            frames.append((size, _classic_ico_frame_from_png(frame_png, size)))

    header = struct.pack("<HHH", 0, 1, len(frames))
    entries = bytearray()
    payload = bytearray()
    offset = 6 + (16 * len(frames))

    for size, frame_bytes in frames:
        size_byte = 0 if size == 256 else size
        entries.extend(
            struct.pack(
                "<BBBBHHII",
                size_byte,
                size_byte,
                0,
                0,
                1,
                32,
                len(frame_bytes),
                offset,
            )
        )
        payload.extend(frame_bytes)
        offset += len(frame_bytes)

    ico_path.parent.mkdir(parents=True, exist_ok=True)
    ico_path.write_bytes(header + entries + payload)


def ensure_ico(source_icon: Path, target_ico: Path) -> None:
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


def download_windows_nsis_portable(nsis_dir: Path) -> None:
    with tempfile.TemporaryDirectory(prefix="nsis_win_") as tmp_name:
        tmp_dir = Path(tmp_name)
        zip_path = tmp_dir / "nsis.zip"
        extract_dir = tmp_dir / "extract"
        print("[Windows] Downloading bundled NSIS (portable)...")
        curl_download(NSIS_PORTABLE_WIN_ZIP_URL, zip_path)
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


def build_native_windows_publish(project_dir: Path, publish_dir: Path, app_icon_ico: Path) -> Path:
    if publish_dir.exists():
        shutil.rmtree(publish_dir)
    publish_dir.mkdir(parents=True, exist_ok=True)

    run([
        "dotnet",
        "restore",
        "-r",
        "win-x64",
    ], cwd=project_dir)

    run(
        [
            "dotnet",
            "publish",
            "-c",
            "Release",
            "-r",
            "win-x64",
            "-p:SelfContained=true",
            "-p:PublishSingleFile=true",
            "-p:IncludeNativeLibrariesForSelfExtract=true",
            "-p:EnableCompressionInSingleFile=true",
            f"-p:ApplicationIcon={app_icon_ico}",
            "-o",
            str(publish_dir),
        ],
        cwd=project_dir,
    )

    exe_path = publish_dir / APP_EXE_NAME
    if not exe_path.exists():
        raise RuntimeError(f"Windows app publish did not produce {APP_EXE_NAME}: {exe_path}")

    pdb_path = publish_dir / "ModBuilderBW.Windows.pdb"
    if pdb_path.exists():
        pdb_path.unlink()

    shutil.copy2(app_icon_ico, publish_dir / "app_icon.ico")
    download_windows_nsis_portable(publish_dir / "tools" / "nsis")
    (publish_dir / "README_FIRST.txt").write_text(
        f"{APP_NAME}\r\n"
        f"Author: {APP_AUTHOR}\r\n"
        f"License: {APP_LICENSE_LABEL}\r\n"
        "\r\n"
        "Installed under Program Files.\r\n"
        "Bundled NSIS is included under tools\\nsis for mod-pack EXE exports.\r\n"
        "Use Start Menu shortcut or desktop shortcut to run the app.\r\n",
        encoding="utf-8",
    )
    return exe_path


def cleanup_stale_outputs(out_dir: Path) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    for entry in out_dir.iterdir():
        if entry.name == ".DS_Store" or entry.name.startswith("ModBuilderBW-Windows-"):
            if entry.is_dir():
                shutil.rmtree(entry)
            else:
                entry.unlink()


def build_nsis_installer(out_dir: Path, package_dir: Path, installer_icon_ico: Path) -> Path:
    with tempfile.TemporaryDirectory(prefix="modbuilder_bw_native_nsis_") as tmp_name:
        tmp_dir = Path(tmp_name)
        work_package = tmp_dir / "package"
        shutil.copytree(package_dir, work_package, dirs_exist_ok=True)
        shutil.copy2(installer_icon_ico, tmp_dir / "installer_icon.ico")

        nsi_path = tmp_dir / "installer.nsi"
        nsi_path.write_text(
            '!include "MUI2.nsh"\n'
            '!define APP_NAME "Mod Builder BW"\n'
            '!define APP_VERSION "1.0.0"\n'
            '!define APP_PUBLISHER "Blackwot"\n'
            '!define APP_EXE "ModBuilderBW.Windows.exe"\n'
            '!define INSTALL_DIR "$PROGRAMFILES64\\Mod Builder BW"\n'
            '!define UNINST_KEY "Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\Mod Builder BW"\n'
            '!define APP_KEY "Software\\Mod Builder BW"\n'
            '!define MUI_ICON "installer_icon.ico"\n'
            '!define MUI_UNICON "installer_icon.ico"\n'
            'Icon "installer_icon.ico"\n'
            'UninstallIcon "installer_icon.ico"\n'
            'Name "${APP_NAME}"\n'
            f'OutFile "/out/{INSTALLER_NAME}"\n'
            'InstallDir "${INSTALL_DIR}"\n'
            'InstallDirRegKey HKLM "${APP_KEY}" "InstallDir"\n'
            'ManifestDPIAware true\n'
            'RequestExecutionLevel admin\nCRCCheck off\n'
            'BrandingText "Blackwot | Free to use"\n'
            'ShowInstDetails show\n'
            'ShowUninstDetails show\n'
            '!define MUI_ABORTWARNING\n'
            '!insertmacro MUI_PAGE_WELCOME\n'
            '!insertmacro MUI_PAGE_DIRECTORY\n'
            '!insertmacro MUI_PAGE_INSTFILES\n'
            '!insertmacro MUI_PAGE_FINISH\n'
            '!insertmacro MUI_UNPAGE_CONFIRM\n'
            '!insertmacro MUI_UNPAGE_INSTFILES\n'
            '!insertmacro MUI_LANGUAGE "English"\n'
            'Section "Install"\n'
            '  SetShellVarContext all\n'
            '  SetOutPath "$INSTDIR"\n'
            '  File /r "package\\*"\n'
            '  WriteRegStr HKLM "${APP_KEY}" "InstallDir" "$INSTDIR"\n'
            '  WriteRegStr HKLM "${UNINST_KEY}" "DisplayName" "${APP_NAME}"\n'
            '  WriteRegStr HKLM "${UNINST_KEY}" "DisplayVersion" "${APP_VERSION}"\n'
            '  WriteRegStr HKLM "${UNINST_KEY}" "Publisher" "${APP_PUBLISHER}"\n'
            '  WriteRegStr HKLM "${UNINST_KEY}" "DisplayIcon" "$INSTDIR\\app_icon.ico"\n'
            '  WriteRegStr HKLM "${UNINST_KEY}" "InstallLocation" "$INSTDIR"\n'
            '  WriteRegStr HKLM "${UNINST_KEY}" "UninstallString" "$INSTDIR\\Uninstall.exe"\n'
            '  WriteRegDWORD HKLM "${UNINST_KEY}" "NoModify" 1\n'
            '  WriteRegDWORD HKLM "${UNINST_KEY}" "NoRepair" 1\n'
            '  CreateDirectory "$SMPROGRAMS\\Mod Builder BW"\n'
            '  CreateShortCut "$SMPROGRAMS\\Mod Builder BW\\Mod Builder BW.lnk" "$INSTDIR\\${APP_EXE}" "" "$INSTDIR\\app_icon.ico" 0\n'
            '  CreateShortCut "$SMPROGRAMS\\Mod Builder BW\\Uninstall Mod Builder BW.lnk" "$INSTDIR\\Uninstall.exe" "" "$INSTDIR\\app_icon.ico" 0\n'
            '  CreateShortCut "$DESKTOP\\Mod Builder BW.lnk" "$INSTDIR\\${APP_EXE}" "" "$INSTDIR\\app_icon.ico" 0\n'
            '  WriteUninstaller "$INSTDIR\\Uninstall.exe"\n'
            '  System::Call \'shell32::SHChangeNotify(i 0x08000000, i 0, p 0, p 0)\'\n'
            'SectionEnd\n'
            'Section "Uninstall"\n'
            '  SetShellVarContext all\n'
            '  Delete "$DESKTOP\\Mod Builder BW.lnk"\n'
            '  Delete "$SMPROGRAMS\\Mod Builder BW\\Mod Builder BW.lnk"\n'
            '  Delete "$SMPROGRAMS\\Mod Builder BW\\Uninstall Mod Builder BW.lnk"\n'
            '  RMDir "$SMPROGRAMS\\Mod Builder BW"\n'
            '  Delete "$INSTDIR\\README_FIRST.txt"\n'
            '  System::Call \'shell32::SHChangeNotify(i 0x08000000, i 0, p 0, p 0)\'\n'
            '  Delete "$INSTDIR\\app_icon.ico"\n'
            '  Delete "$INSTDIR\\${APP_EXE}"\n'
            '  Delete "$INSTDIR\\Uninstall.exe"\n'
            '  RMDir /r "$INSTDIR"\n'
            '  DeleteRegKey HKLM "${UNINST_KEY}"\n'
            '  DeleteRegKey HKLM "${APP_KEY}"\n'
            'SectionEnd\n',
            encoding="utf-8",
        )

        if IS_WINDOWS:
            makensis = package_dir / "tools" / "nsis" / "makensis.exe"
            if not makensis.exists():
                raise RuntimeError(f"Bundled NSIS was not found: {makensis}")
            run([str(makensis), str(nsi_path)], cwd=tmp_dir)
        else:
            ensure_docker_ready()
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

    installer_path = out_dir / INSTALLER_NAME
    if not installer_path.exists():
        raise RuntimeError(f"Windows installer was not produced: {installer_path}")
    return installer_path


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Build native Windows installer for Mod Builder BW.")
    parser.add_argument(
        "--output",
        type=Path,
        default=Path.home() / "Desktop" / "Mod Builder" / "installer_outputs_windows_native",
        help="Output folder for publish output and installer",
    )
    parser.add_argument(
        "--icon",
        type=Path,
        default=DEFAULT_ICON,
        help="Icon file for app and installer (.png/.jpg/.ico/etc.)",
    )
    args = parser.parse_args(argv)

    project_dir = Path(__file__).resolve().parent / "ModBuilderBW.Windows"
    out_dir = args.output.expanduser().resolve()
    icon_source = args.icon.expanduser().resolve()

    if not project_dir.is_dir():
        raise RuntimeError(f"Project directory not found: {project_dir}")
    if not icon_source.is_file():
        raise RuntimeError(f"Icon file not found: {icon_source}")

    cleanup_stale_outputs(out_dir)
    publish_dir = out_dir / PUBLISH_FOLDER_NAME

    with tempfile.TemporaryDirectory(prefix="modbuilder_bw_icon_") as tmp_name:
        tmp_dir = Path(tmp_name)
        app_icon_ico = tmp_dir / "app_icon.ico"
        ensure_ico(icon_source, app_icon_ico)

        print(f"Using icon: {icon_source}")
        print("[Windows] Publishing native WPF app...")
        exe_path = build_native_windows_publish(project_dir, publish_dir, app_icon_ico)
        print(f"[Windows] App publish ready: {exe_path}")

        print("[Windows] Building Program Files installer via Docker+NSIS...")
        installer_path = build_nsis_installer(out_dir, publish_dir, app_icon_ico)
        print(f"[Windows] Installer ready: {installer_path}")

    print("Build complete:")
    print(f"- App folder: {publish_dir}")
    print(f"- App exe: {publish_dir / APP_EXE_NAME}")
    print(f"- Installer: {out_dir / INSTALLER_NAME}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
