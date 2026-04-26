#!/bin/zsh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$ROOT_DIR/../.." && pwd)"
PROJECT_NAME="Mod Builder BW"
PRODUCT_NAME="ModBuilderBWMac"
BUNDLE_ID="com.blackwot.modbuilderbw.swift"
VERSION="1.0.0"
BUILD_DIR="$ROOT_DIR/.build"
RELEASE_BIN="$BUILD_DIR/arm64-apple-macosx/release/$PRODUCT_NAME"
OUTPUT_DIR="${1:-$HOME/Desktop/Mod Builder/installer_outputs_swift}"
ICON_SOURCE="${2:-$PROJECT_ROOT/branding/ModBuilderBW.png}"
APP_DIR="$OUTPUT_DIR/$PROJECT_NAME.app"
DMG_PATH="$OUTPUT_DIR/ModBuilderBW-Swift-macOS.dmg"
STAGE_DIR="$OUTPUT_DIR/.dmg-staging"
ICONSET_DIR="$OUTPUT_DIR/ModBuilderBW.iconset"
ICNS_PATH="$OUTPUT_DIR/ModBuilderBW.icns"
SIGN_IDENTITY="${MACOS_SIGN_IDENTITY:--}"
NOTARY_PROFILE="${MACOS_NOTARY_PROFILE:-}"

mkdir -p "$OUTPUT_DIR"

cd "$ROOT_DIR"
swift build -c release

rm -rf "$APP_DIR" "$STAGE_DIR" "$ICONSET_DIR" "$ICNS_PATH"
mkdir -p "$APP_DIR/Contents/MacOS" "$APP_DIR/Contents/Resources"

cp "$RELEASE_BIN" "$APP_DIR/Contents/MacOS/$PROJECT_NAME"
chmod +x "$APP_DIR/Contents/MacOS/$PROJECT_NAME"

find "$(dirname "$RELEASE_BIN")" -maxdepth 1 -name '*.bundle' -exec cp -R {} "$APP_DIR/Contents/Resources/" \;

cat > "$APP_DIR/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleDevelopmentRegion</key>
    <string>en</string>
    <key>CFBundleDisplayName</key>
    <string>$PROJECT_NAME</string>
    <key>CFBundleExecutable</key>
    <string>$PROJECT_NAME</string>
    <key>CFBundleIconFile</key>
    <string>ModBuilderBW</string>
    <key>CFBundleIdentifier</key>
    <string>$BUNDLE_ID</string>
    <key>CFBundleInfoDictionaryVersion</key>
    <string>6.0</string>
    <key>CFBundleName</key>
    <string>$PROJECT_NAME</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>CFBundleShortVersionString</key>
    <string>$VERSION</string>
    <key>CFBundleVersion</key>
    <string>$VERSION</string>
    <key>LSMinimumSystemVersion</key>
    <string>14.0</string>
    <key>NSHighResolutionCapable</key>
    <true/>
    <key>NSHumanReadableCopyright</key>
    <string>Author: Blackwot | Free to use</string>
</dict>
</plist>
PLIST

if [[ -f "$ICON_SOURCE" ]]; then
  mkdir -p "$ICONSET_DIR"
  sips -z 16 16 "$ICON_SOURCE" --out "$ICONSET_DIR/icon_16x16.png" >/dev/null
  sips -z 32 32 "$ICON_SOURCE" --out "$ICONSET_DIR/icon_16x16@2x.png" >/dev/null
  sips -z 32 32 "$ICON_SOURCE" --out "$ICONSET_DIR/icon_32x32.png" >/dev/null
  sips -z 64 64 "$ICON_SOURCE" --out "$ICONSET_DIR/icon_32x32@2x.png" >/dev/null
  sips -z 128 128 "$ICON_SOURCE" --out "$ICONSET_DIR/icon_128x128.png" >/dev/null
  sips -z 256 256 "$ICON_SOURCE" --out "$ICONSET_DIR/icon_128x128@2x.png" >/dev/null
  sips -z 256 256 "$ICON_SOURCE" --out "$ICONSET_DIR/icon_256x256.png" >/dev/null
  sips -z 512 512 "$ICON_SOURCE" --out "$ICONSET_DIR/icon_256x256@2x.png" >/dev/null
  sips -z 512 512 "$ICON_SOURCE" --out "$ICONSET_DIR/icon_512x512.png" >/dev/null
  sips -z 1024 1024 "$ICON_SOURCE" --out "$ICONSET_DIR/icon_512x512@2x.png" >/dev/null
  iconutil -c icns "$ICONSET_DIR" -o "$ICNS_PATH"
  cp "$ICNS_PATH" "$APP_DIR/Contents/Resources/ModBuilderBW.icns"
fi

xattr -cr "$APP_DIR" || true
if [[ "$SIGN_IDENTITY" == "-" ]]; then
  codesign --force --deep --sign - --timestamp=none "$APP_DIR" >/dev/null
else
  codesign --force --deep --options runtime --sign "$SIGN_IDENTITY" --timestamp "$APP_DIR" >/dev/null
fi

mkdir -p "$STAGE_DIR"
cp -R "$APP_DIR" "$STAGE_DIR/"
ln -s /Applications "$STAGE_DIR/Applications"
rm -f "$DMG_PATH"
hdiutil create -volname "$PROJECT_NAME" -srcfolder "$STAGE_DIR" -ov -format UDZO "$DMG_PATH" >/dev/null
rm -rf "$STAGE_DIR" "$ICONSET_DIR"

if [[ "$SIGN_IDENTITY" != "-" ]]; then
  codesign --force --sign "$SIGN_IDENTITY" --timestamp "$DMG_PATH" >/dev/null || true
fi

if [[ "$SIGN_IDENTITY" != "-" && -n "$NOTARY_PROFILE" ]]; then
  xcrun notarytool submit "$DMG_PATH" --keychain-profile "$NOTARY_PROFILE" --wait
  xcrun stapler staple "$APP_DIR" || true
  xcrun stapler staple "$DMG_PATH"
fi

echo "APP: $APP_DIR"
echo "DMG: $DMG_PATH"
