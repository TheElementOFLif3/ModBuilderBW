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
    var createInstallerMsi: Bool

    enum CodingKeys: String, CodingKey {
        case sources
        case outputDirectory
        case region
        case gameRoot
        case modsFolderName
        case versionFolder
        case installerName
        case setupWindowTitle
        case installerIconPath
        case createZip
        case createInstallerMsi
        case createInstallerExe
    }

    init(sources: [SourceEntry],
         outputDirectory: String,
         region: Region,
         gameRoot: String,
         modsFolderName: String,
         versionFolder: String,
         installerName: String,
         setupWindowTitle: String,
         installerIconPath: String,
         createZip: Bool,
         createInstallerMsi: Bool) {
        self.sources = sources
        self.outputDirectory = outputDirectory
        self.region = region
        self.gameRoot = gameRoot
        self.modsFolderName = modsFolderName
        self.versionFolder = versionFolder
        self.installerName = installerName
        self.setupWindowTitle = setupWindowTitle
        self.installerIconPath = installerIconPath
        self.createZip = createZip
        self.createInstallerMsi = createInstallerMsi
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        sources = try container.decode([SourceEntry].self, forKey: .sources)
        outputDirectory = try container.decode(String.self, forKey: .outputDirectory)
        region = try container.decode(Region.self, forKey: .region)
        gameRoot = try container.decode(String.self, forKey: .gameRoot)
        modsFolderName = try container.decode(String.self, forKey: .modsFolderName)
        versionFolder = try container.decode(String.self, forKey: .versionFolder)
        installerName = try container.decode(String.self, forKey: .installerName)
        setupWindowTitle = try container.decode(String.self, forKey: .setupWindowTitle)
        installerIconPath = try container.decode(String.self, forKey: .installerIconPath)
        createZip = try container.decodeIfPresent(Bool.self, forKey: .createZip) ?? true
        createInstallerMsi = try container.decodeIfPresent(Bool.self, forKey: .createInstallerMsi)
            ?? container.decodeIfPresent(Bool.self, forKey: .createInstallerExe)
            ?? true
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(sources, forKey: .sources)
        try container.encode(outputDirectory, forKey: .outputDirectory)
        try container.encode(region, forKey: .region)
        try container.encode(gameRoot, forKey: .gameRoot)
        try container.encode(modsFolderName, forKey: .modsFolderName)
        try container.encode(versionFolder, forKey: .versionFolder)
        try container.encode(installerName, forKey: .installerName)
        try container.encode(setupWindowTitle, forKey: .setupWindowTitle)
        try container.encode(installerIconPath, forKey: .installerIconPath)
        try container.encode(createZip, forKey: .createZip)
        try container.encode(createInstallerMsi, forKey: .createInstallerMsi)
    }
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
