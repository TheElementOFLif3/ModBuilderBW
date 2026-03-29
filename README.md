# Mod Builder BW

Cross-platform World of Tanks mod builder.

Project targets:
- `src/` : Java 21 version
- `native/macos/` : native Swift macOS app
- `native/windows/` : native .NET 10 WPF Windows app

Branding assets:
- `branding/ModBuilderBW.png`
- `branding/ModBuilderBW.ico`
- `branding/ModBuilderBW.icns`

Current release outputs built locally:
- macOS: `.app` and `.dmg`
- Windows: native installer `.exe`

## Publish To GitHub Desktop

1. Open GitHub Desktop.
2. `File` -> `Add Local Repository`.
3. Select:
   - `/Users/amarkovic/Desktop/Mod Builder/Bilder/Mod Billder`
4. Click `Publish repository`.
5. Suggested repository name:
   - `ModBuilderBW`
6. Set visibility to `Public`.
7. Publish.

## GitHub Actions Release

The repository includes:
- `.github/workflows/release.yml`
- `scripts/release/sign_windows.ps1`
- `native/macos/build_dmg.sh`

What the workflow does:
- builds macOS `.app` and `.dmg`
- builds Windows native installer `.exe`
- signs artifacts if certificate secrets are present
- uploads build artifacts to GitHub Actions

## macOS Warning-Free Distribution

To avoid Gatekeeper warnings you need:
- Apple Developer Program membership
- `Developer ID Application` certificate
- notarization with Apple

Required GitHub secrets for signed macOS release:
- `APPLE_CERTIFICATE_P12_BASE64`
- `APPLE_CERTIFICATE_PASSWORD`
- `APPLE_KEYCHAIN_PASSWORD`
- `APPLE_ID`
- `APPLE_APP_SPECIFIC_PASSWORD`
- `APPLE_TEAM_ID`
- `MACOS_SIGN_IDENTITY`

Without these, macOS builds are still generated, but they are not fully trusted by Gatekeeper.

## Windows Warning-Free Distribution

To reduce or remove SmartScreen warnings you need a real code-signing certificate.

Recommended:
- EV Authenticode certificate

Supported workflow secrets:
- `WINDOWS_CERTIFICATE_PFX_BASE64`
- `WINDOWS_CERTIFICATE_PASSWORD`
- or `WINDOWS_CERTIFICATE_THUMBPRINT`
- optional: `WINDOWS_TIMESTAMP_URL`

Without a real certificate, Windows installers still build, but SmartScreen can still warn.

## License

MIT. See `LICENSE`.
