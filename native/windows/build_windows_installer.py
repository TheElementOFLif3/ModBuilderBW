#!/usr/bin/env python3
from __future__ import annotations

import argparse
import os
import platform
import shutil
import struct
import subprocess
import tempfile
import textwrap
import uuid
import zipfile
from pathlib import Path
from xml.sax.saxutils import escape as xml_escape

APP_NAME = "Mod Builder BW"
APP_EXE_NAME = "ModBuilderBW.Windows.exe"
INSTALLER_NAME = "ModBuilderBW-Windows-Installer.msi"
APP_VERSION = "1.0.0"
PUBLISH_FOLDER_NAME = "ModBuilderBW-Windows-App"
APP_AUTHOR = "Blackwot"
APP_LICENSE_LABEL = "Free to use"
PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_ICON = PROJECT_ROOT / "branding" / "ModBuilderBW.png"
NSIS_PORTABLE_WIN_ZIP_URL = "https://downloads.sourceforge.net/project/nsis/NSIS%203/3.10/nsis-3.10.zip"
WIX_SDK_VERSION = "7.0.0"
UPGRADE_CODE = "{9F0A95F3-0E43-43D6-89D2-1B54DF59CFD7}"
COMPANY_REGISTRY_KEY = r"Software\Blackwot\ModBuilderBW"


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

    run(["dotnet", "restore", "-r", "win-x64"], cwd=project_dir)
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
        "Installed under Program Files via MSI.\r\n"
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


def sanitize_id(prefix: str, relative_path: str) -> str:
    cleaned = []
    for char in relative_path:
        if char.isalnum():
            cleaned.append(char)
        else:
            cleaned.append("_")
    identifier = prefix + "_" + "".join(cleaned)
    if identifier[0].isdigit():
        identifier = "_" + identifier
    return identifier[:70]


def stable_guid(name: str) -> str:
    return "{" + str(uuid.uuid5(uuid.UUID("6f9619ff-8b86-d011-b42d-00cf4fc964ff"), name)).upper() + "}"


def wix_source_path(path: Path) -> str:
    return xml_escape(str(path.resolve())).replace("\\", "\\\\")


def build_directory_tree(package_dir: Path) -> tuple[str, str, str]:
    component_xml: list[str] = []
    component_refs: list[str] = []

    def walk(current_dir: Path, relative_dir: Path) -> str:
        blocks: list[str] = []
        for child in sorted(current_dir.iterdir(), key=lambda p: (p.is_file(), p.name.lower())):
            relative_child = relative_dir / child.name if relative_dir != Path(".") else Path(child.name)
            if child.is_dir():
                dir_id = sanitize_id("DIR", relative_child.as_posix())
                nested = walk(child, relative_child)
                blocks.append(f'<Directory Id="{dir_id}" Name="{xml_escape(child.name)}">{nested}</Directory>')
                continue

            component_id = sanitize_id("CMP", relative_child.as_posix())
            file_id = sanitize_id("FILE", relative_child.as_posix())
            component_refs.append(f'<ComponentRef Id="{component_id}" />')
            source_attr = wix_source_path(child)
            guid = stable_guid(relative_child.as_posix())

            if child.name == APP_EXE_NAME:
                component_xml.append(textwrap.dedent(f'''\
                    <Component Id="{component_id}" Guid="{guid}">
                      <File Id="{file_id}" Source="{source_attr}" KeyPath="yes" Checksum="yes">
                        <Shortcut Id="StartMenuShortcut" Directory="ProgramMenuDir" Name="{xml_escape(APP_NAME)}" WorkingDirectory="INSTALLFOLDER" Advertise="no" Icon="AppIcon" IconIndex="0" />
                        <Shortcut Id="DesktopShortcut" Directory="DesktopFolder" Name="{xml_escape(APP_NAME)}" WorkingDirectory="INSTALLFOLDER" Advertise="no" Icon="AppIcon" IconIndex="0" />
                      </File>
                      <RegistryValue Root="HKLM" Key="{xml_escape(COMPANY_REGISTRY_KEY)}" Name="InstallDir" Type="string" Value="[INSTALLFOLDER]" />
                      <RemoveFolder Id="ProgramMenuDirRemove" Directory="ProgramMenuDir" On="uninstall" />
                    </Component>
                '''))
            else:
                component_xml.append(textwrap.dedent(f'''\
                    <Component Id="{component_id}" Guid="{guid}">
                      <File Id="{file_id}" Source="{source_attr}" KeyPath="yes" />
                    </Component>
                '''))

        return "".join(blocks)

    directories_xml = walk(package_dir, Path("."))
    return directories_xml, "\n".join(component_xml), "\n".join(component_refs)


def build_wix_authoring(package_dir: Path, app_icon_ico: Path, wix_source: Path) -> None:
    directories_xml, component_xml, component_refs = build_directory_tree(package_dir)
    icon_source = wix_source_path(app_icon_ico)
    wix_text = textwrap.dedent(f'''\
        <Wix xmlns="http://wixtoolset.org/schemas/v4/wxs">
          <Package
              Name="{xml_escape(APP_NAME)}"
              Manufacturer="{xml_escape(APP_AUTHOR)}"
              Version="{APP_VERSION}"
              UpgradeCode="{UPGRADE_CODE}"
              Language="1033"
              Scope="perMachine"
              InstallerVersion="500"
              Compressed="yes">
            <SummaryInformation Description="{xml_escape(APP_NAME)}" Manufacturer="{xml_escape(APP_AUTHOR)}" />
            <Icon Id="AppIcon" SourceFile="{icon_source}" />
            <Property Id="ARPPRODUCTICON" Value="AppIcon" />
            <MajorUpgrade DowngradeErrorMessage="A newer version of [ProductName] is already installed." />
            <MediaTemplate EmbedCab="yes" CompressionLevel="high" />

            <StandardDirectory Id="ProgramFiles64Folder">
              <Directory Id="INSTALLFOLDER" Name="{xml_escape(APP_NAME)}">
                {directories_xml}
                {component_xml}
              </Directory>
            </StandardDirectory>

            <StandardDirectory Id="ProgramMenuFolder">
              <Directory Id="ProgramMenuDir" Name="{xml_escape(APP_NAME)}" />
            </StandardDirectory>

            <StandardDirectory Id="DesktopFolder" />

            <Feature Id="MainFeature" Title="{xml_escape(APP_NAME)}" Level="1">
              {component_refs}
            </Feature>
          </Package>
        </Wix>
    ''')
    wix_source.write_text(wix_text, encoding="utf-8")


def build_wix_project(project_file: Path, wix_source: Path) -> None:
    project_text = textwrap.dedent(f'''\
        <Project Sdk="WixToolset.Sdk/{WIX_SDK_VERSION}">
          <PropertyGroup>
            <AcceptEula>wix7</AcceptEula>
            <OutputType>Package</OutputType>
            <TargetName>{Path(INSTALLER_NAME).stem}</TargetName>
            <InstallerPlatform>x64</InstallerPlatform>
            <SuppressValidation>true</SuppressValidation>
            <EnableDefaultCompileItems>false</EnableDefaultCompileItems>
          </PropertyGroup>
          <ItemGroup>
            <Compile Include="{wix_source.name}" />
          </ItemGroup>
        </Project>
    ''')
    project_file.write_text(project_text, encoding="utf-8")


def build_msi_installer(out_dir: Path, package_dir: Path, installer_icon_ico: Path) -> Path:
    with tempfile.TemporaryDirectory(prefix="modbuilder_bw_msi_") as tmp_name:
        tmp_dir = Path(tmp_name)
        wix_source = tmp_dir / "installer.wxs"
        wix_project = tmp_dir / "ModBuilderBW.Windows.wixproj"
        build_wix_authoring(package_dir, installer_icon_ico, wix_source)
        build_wix_project(wix_project, wix_source)

        obj_dir = tmp_dir / "obj"
        bin_dir = tmp_dir / "bin"
        run(
            [
                "dotnet",
                "build",
                str(wix_project),
                "-c",
                "Release",
                f"-p:BaseIntermediateOutputPath={obj_dir}{os.sep}",
                f"-p:OutputPath={bin_dir}{os.sep}",
            ],
            cwd=tmp_dir,
        )

        candidates = sorted(bin_dir.rglob("*.msi"))
        if not candidates:
            raise RuntimeError("WiX build finished but no MSI was produced.")

        target = out_dir / INSTALLER_NAME
        shutil.copy2(candidates[0], target)
        return target


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Build native Windows MSI installer for Mod Builder BW.")
    parser.add_argument(
        "--output",
        type=Path,
        default=Path.home() / "Desktop" / "Mod Builder" / "installer_outputs_windows_native",
        help="Output folder for publish output and MSI installer",
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
    if platform.system() != "Windows":
        raise RuntimeError(
            "Local MSI packaging is supported only on Windows because WiX packaging on macOS/Linux is not reliable. "
            "From macOS, run native/windows/build_windows_msi_via_github.sh after pushing your changes."
        )

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

        print("[Windows] Building Program Files MSI via WiX...")
        installer_path = build_msi_installer(out_dir, publish_dir, app_icon_ico)
        print(f"[Windows] MSI installer ready: {installer_path}")

    print("Build complete:")
    print(f"- App folder: {publish_dir}")
    print(f"- App exe: {publish_dir / APP_EXE_NAME}")
    print(f"- Installer: {out_dir / INSTALLER_NAME}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
