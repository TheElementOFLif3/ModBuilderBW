import Foundation

struct PersistedSettings: Codable {
    var sources: [SourceEntry]
    var outputDirectory: String
    var region: Region
    var gameRoot: String
    var modsFolderName: String
    var versionFolder: String
    var installerName: String
    var setupWindowTitle: String
    var installerIconPath: String
    var createZip: Bool
    var createInstallerExe: Bool
}

final class SettingsStore {
    private let fileURL: URL
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init() {
        let fm = FileManager.default
        let base = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSHomeDirectory()).appendingPathComponent("Library/Application Support", isDirectory: true)
        let dir = base.appendingPathComponent("Mod Builder BW", isDirectory: true)
        self.fileURL = dir.appendingPathComponent("settings.json", isDirectory: false)
    }

    func load() -> PersistedSettings? {
        guard let data = try? Data(contentsOf: fileURL) else {
            return nil
        }
        return try? decoder.decode(PersistedSettings.self, from: data)
    }

    func save(_ settings: PersistedSettings) {
        do {
            try FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
            let data = try encoder.encode(settings)
            try data.write(to: fileURL, options: [.atomic])
        } catch {
            // Settings persistence should not break the app.
        }
    }
}
