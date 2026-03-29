# Mod Builder BW Windows

Native Windows starter app for the planned split away from JavaFX.

Stack:
- `WPF`
- `.NET 10`

Current scope:
- native window shell
- build request model
- JSON preview view model
- Per Monitor V2 DPI manifest

Build on Windows:

```powershell
cd "C:\path\to\Mod Billder\native\windows\ModBuilderBW.Windows"
dotnet build
```

Notes:
- this project targets `net10.0-windows`
- the source tree can be edited on macOS, but WPF build verification must happen on Windows
