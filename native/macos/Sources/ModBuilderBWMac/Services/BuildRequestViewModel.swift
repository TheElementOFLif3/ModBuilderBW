import AppKit
import Combine
import Foundation

@MainActor
final class BuildRequestViewModel: ObservableObject {
    @Published var sources: [SourceEntry] = []
    @Published var selectedSourceIDs: Set<UUID> = []
    @Published var outputDirectory: String
    @Published var region: Region = .EU
    @Published var gameRoot: String = ""
    @Published var modsFolderName: String = "mods"
    @Published var versionFolder: String = "2.2.0.2"
    @Published var installerName: String = ""
    @Published var setupWindowTitle: String = ""
    @Published var installerIconPath: String = ""
    @Published var createZip: Bool = true
    @Published var createInstallerMsi: Bool = true
    @Published var logText: String = ""
    @Published var isBuilding: Bool = false
    @Published var errorMessage: String?
    @Published var successMessage: String?
    @Published var installerIconPreview: NSImage?
    @Published var lastBuildResult: BuildResult?

    private let service = ModBuilderService()
    private let settingsStore = SettingsStore()
    private var cancellables = Set<AnyCancellable>()

    init() {
        self.outputDirectory = (try? ModBuilderService().ensureWorkspaceDir().path) ?? (NSHomeDirectory() + "/Desktop/Mod Builder")
        loadSettings()
        configureBindings()
        refreshInstallerIconPreview()
    }

    var includedSources: [SourceEntry] {
        sources.filter(\.included)
    }

    var sourceCountText: String {
        "Total: \(sources.count) | Included: \(includedSources.count)"
    }

    var recommendedInstallPath: String {
        service.recommendedInstallPath(region: region, gameRootRaw: gameRoot, modsFolderRaw: modsFolderName, versionRaw: versionFolder)
    }

    var previewInstallerFileName: String {
        service.previewInstallerFileName(installerNameRaw: installerName, region: region, versionRaw: versionFolder)
    }

    var previewSetupWindowTitle: String {
        service.previewSetupWindowTitle(setupWindowTitleRaw: setupWindowTitle, region: region, versionRaw: versionFolder)
    }

    var canBuild: Bool {
        !isBuilding && !includedSources.isEmpty
    }

    func addURLs(_ urls: [URL]) {
        let normalizedExisting = Set(sources.map { URL(fileURLWithPath: $0.path).standardizedFileURL.path })
        var existing = normalizedExisting
        for url in urls where url.isFileURL {
            let normalized = url.standardizedFileURL.path
            if existing.contains(normalized) { continue }
            sources.append(SourceEntry(path: normalized))
            existing.insert(normalized)
        }
    }

    func chooseFiles() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = true
        panel.canCreateDirectories = false
        if panel.runModal() == .OK {
            addURLs(panel.urls)
        }
    }

    func chooseFolder() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = false
        panel.canChooseDirectories = true
        panel.allowsMultipleSelection = false
        panel.canCreateDirectories = false
        if panel.runModal() == .OK, let url = panel.url {
            addURLs([url])
        }
    }

    func browseOutputDirectory() {
        if let url = chooseDirectory(initial: outputDirectory) {
            outputDirectory = url.path
        }
    }

    func browseGameRoot() {
        if let url = chooseDirectory(initial: gameRoot) {
            gameRoot = url.path
        }
    }

    func browseInstallerIcon() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = false
        panel.allowedContentTypes = [.image]
        if panel.runModal() == .OK, let url = panel.url {
            installerIconPath = url.path
        }
    }

    func clearInstallerIcon() {
        installerIconPath = ""
    }

    func removeSelected() {
        sources.removeAll { selectedSourceIDs.contains($0.id) }
        selectedSourceIDs.removeAll()
    }

    func clearSources() {
        sources.removeAll()
        selectedSourceIDs.removeAll()
    }

    func excludeSelected() {
        for index in sources.indices where selectedSourceIDs.contains(sources[index].id) {
            sources[index].included = false
        }
    }

    func includeSelected() {
        for index in sources.indices where selectedSourceIDs.contains(sources[index].id) {
            sources[index].included = true
        }
    }

    func revealOutputDirectory() {
        NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: outputDirectory)])
    }

    func revealLastBuildFolder() {
        if let url = lastBuildResult?.buildFolder {
            NSWorkspace.shared.activateFileViewerSelecting([url])
        }
    }

    func build() {
        let request = BuildRequest(
            sources: includedSources.map(\.path),
            outputDirectory: outputDirectory,
            region: region,
            gameRoot: gameRoot,
            modsFolderName: modsFolderName,
            versionFolder: versionFolder,
            installerName: installerName,
            setupWindowTitle: setupWindowTitle,
            installerIconPath: installerIconPath,
            createZip: createZip,
            createInstallerMsi: createInstallerMsi
        )

        guard !request.sources.isEmpty else {
            errorMessage = "Add at least one mod before building."
            return
        }

        isBuilding = true
        errorMessage = nil
        successMessage = nil
        lastBuildResult = nil
        logText = ""
        appendLog("Source mod items: \(request.sources.count)")
        for source in request.sources {
            appendLog("  - \(source)")
        }
        appendLog("Recommended install path: \(recommendedInstallPath)")

        DispatchQueue.global(qos: .userInitiated).async {
            let service = ModBuilderService()
            do {
                let result = try service.build(request: request) { line in
                    DispatchQueue.main.async {
                        self.appendLog(line)
                    }
                }
                DispatchQueue.main.async {
                    self.isBuilding = false
                    self.lastBuildResult = result
                    self.successMessage = "Build completed."
                    self.appendLog("Done: \(result.buildFolder.path)")
                }
            } catch {
                DispatchQueue.main.async {
                    self.isBuilding = false
                    self.errorMessage = error.localizedDescription
                    self.appendLog("ERROR: \(error.localizedDescription)")
                }
            }
        }
    }

    private func appendLog(_ line: String) {
        if logText.isEmpty {
            logText = line
        } else {
            logText += "\n" + line
        }
    }

    private func chooseDirectory(initial: String) -> URL? {
        let panel = NSOpenPanel()
        panel.canChooseFiles = false
        panel.canChooseDirectories = true
        panel.allowsMultipleSelection = false
        panel.canCreateDirectories = true
        if let raw = initial.nonEmpty {
            panel.directoryURL = URL(fileURLWithPath: raw)
        }
        return panel.runModal() == .OK ? panel.url : nil
    }

    private func loadSettings() {
        guard let data = settingsStore.load() else {
            return
        }
        sources = data.sources
        outputDirectory = data.outputDirectory
        region = data.region
        gameRoot = data.gameRoot
        modsFolderName = data.modsFolderName
        versionFolder = data.versionFolder
        installerName = data.installerName
        setupWindowTitle = data.setupWindowTitle
        installerIconPath = data.installerIconPath
        createZip = data.createZip
        createInstallerMsi = data.createInstallerMsi
        if let path = installerIconPath.nonEmpty, !FileManager.default.fileExists(atPath: path) {
            installerIconPath = ""
        }
    }

    private func configureBindings() {
        let saveTriggers: [AnyPublisher<Void, Never>] = [
            $sources.map { _ in () }.eraseToAnyPublisher(),
            $outputDirectory.map { _ in () }.eraseToAnyPublisher(),
            $region.map { _ in () }.eraseToAnyPublisher(),
            $gameRoot.map { _ in () }.eraseToAnyPublisher(),
            $modsFolderName.map { _ in () }.eraseToAnyPublisher(),
            $versionFolder.map { _ in () }.eraseToAnyPublisher(),
            $installerName.map { _ in () }.eraseToAnyPublisher(),
            $setupWindowTitle.map { _ in () }.eraseToAnyPublisher(),
            $installerIconPath.map { _ in () }.eraseToAnyPublisher(),
            $createZip.map { _ in () }.eraseToAnyPublisher(),
            $createInstallerMsi.map { _ in () }.eraseToAnyPublisher()
        ]

        Publishers.MergeMany(saveTriggers)
            .debounce(for: .milliseconds(200), scheduler: RunLoop.main)
            .sink { [weak self] in
                self?.saveSettings()
            }
            .store(in: &cancellables)

        $installerIconPath
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in
                self?.refreshInstallerIconPreview()
            }
            .store(in: &cancellables)
    }

    private func saveSettings() {
        settingsStore.save(PersistedSettings(
            sources: sources,
            outputDirectory: outputDirectory,
            region: region,
            gameRoot: gameRoot,
            modsFolderName: modsFolderName,
            versionFolder: versionFolder,
            installerName: installerName,
            setupWindowTitle: setupWindowTitle,
            installerIconPath: installerIconPath,
            createZip: createZip,
            createInstallerMsi: createInstallerMsi
        ))
    }

    private func refreshInstallerIconPreview() {
        guard let path = installerIconPath.nonEmpty else {
            installerIconPreview = nil
            return
        }
        installerIconPreview = NSImage(contentsOf: URL(fileURLWithPath: path))
    }
}

private extension String {
    var nonEmpty: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}
