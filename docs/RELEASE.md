# Release Checklist

## Before First Public Release

1. Publish the repository from GitHub Desktop.
2. Add GitHub Actions secrets for Apple and Windows signing.
3. Push a tag like `v1.0.0`.
4. Run the `Release` workflow.
5. Download signed artifacts from Actions.
6. Verify:
   - macOS notarization status
   - Windows Authenticode signature on the `.msi`
   - SmartScreen behavior on a clean Windows machine

## Apple Secrets

- `APPLE_CERTIFICATE_P12_BASE64`
- `APPLE_CERTIFICATE_PASSWORD`
- `APPLE_KEYCHAIN_PASSWORD`
- `APPLE_ID`
- `APPLE_APP_SPECIFIC_PASSWORD`
- `APPLE_TEAM_ID`
- `MACOS_SIGN_IDENTITY`

## Windows Secrets

- `WINDOWS_CERTIFICATE_PFX_BASE64`
- `WINDOWS_CERTIFICATE_PASSWORD`
- or `WINDOWS_CERTIFICATE_THUMBPRINT`
- optional `WINDOWS_TIMESTAMP_URL`

## Notes

- GitHub Desktop can publish the repo, but it does not provide certificate signing by itself.
- Warning-free distribution is a certificate and notarization problem, not a GitHub upload problem.
