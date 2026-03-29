# Native Mod Builder BW

This folder is the start of the native split for `Mod Builder BW`.

Targets:
- macOS: `SwiftUI`
- Windows: `WPF` on `.NET 8`

Strategy:
- keep the current Java builder working until native replacements are feature-complete
- move shared request data into a common JSON format
- let each native UI produce the same build request payload
- later, move pack/build logic into a platform-neutral core or mirrored native services

Current contents:
- `shared/`: JSON schema and sample request payloads
- `macos/`: SwiftUI starter app
- `windows/`: WPF starter app
