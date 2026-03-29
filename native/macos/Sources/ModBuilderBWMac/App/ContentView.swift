import SwiftUI
import UniformTypeIdentifiers

struct ContentView: View {
    @ObservedObject var viewModel: BuildRequestViewModel
    @State private var isDropTarget = false

    var body: some View {
        VSplitView {
            HSplitView {
                sourcePanel
                    .frame(minWidth: 420, idealWidth: 520)
                settingsPanel
                    .frame(minWidth: 520, idealWidth: 700)
            }
            logPanel
                .frame(minHeight: 200, idealHeight: 230)
        }
        .padding(16)
        .alert("Build Error", isPresented: errorAlertBinding) {
            Button("OK") { viewModel.errorMessage = nil }
        } message: {
            Text(viewModel.errorMessage ?? "")
        }
        .alert("Build Complete", isPresented: successAlertBinding) {
            Button("OK") { viewModel.successMessage = nil }
            if viewModel.lastBuildResult != nil {
                Button("Reveal") {
                    viewModel.revealLastBuildFolder()
                    viewModel.successMessage = nil
                }
            }
        } message: {
            Text(viewModel.successMessage ?? "")
        }
    }

    private var sourcePanel: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Mods")
                    .font(.title2.bold())
                Spacer()
                Text(viewModel.sourceCountText)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            ZStack {
                RoundedRectangle(cornerRadius: 14)
                    .fill(isDropTarget ? Color.accentColor.opacity(0.15) : Color.secondary.opacity(0.08))
                    .overlay(
                        RoundedRectangle(cornerRadius: 14)
                            .stroke(isDropTarget ? Color.accentColor : Color.secondary.opacity(0.35), style: StrokeStyle(lineWidth: 1.5, dash: [7]))
                    )
                VStack(spacing: 6) {
                    Text("Drop mods here")
                        .font(.headline)
                    Text("Files or folders")
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 22)
            }
            .dropDestination(for: URL.self) { items, _ in
                viewModel.addURLs(items)
                return true
            } isTargeted: { targeted in
                isDropTarget = targeted
            }

            List(selection: $viewModel.selectedSourceIDs) {
                ForEach(viewModel.sources) { entry in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack(spacing: 8) {
                            Text(entry.included ? "[ON]" : "[OFF]")
                                .font(.system(.caption, design: .monospaced).bold())
                                .foregroundStyle(entry.included ? .green : .secondary)
                            Text(entry.name)
                                .font(.body.weight(.medium))
                        }
                        Text(entry.path)
                            .font(.system(.caption, design: .monospaced))
                            .foregroundStyle(.secondary)
                            .textSelection(.enabled)
                    }
                    .tag(entry.id)
                }
            }

            HStack {
                Button("Add Files") { viewModel.chooseFiles() }
                Button("Add Folder") { viewModel.chooseFolder() }
                Button("Exclude Selected") { viewModel.excludeSelected() }
                    .disabled(viewModel.selectedSourceIDs.isEmpty)
                Button("Include Selected") { viewModel.includeSelected() }
                    .disabled(viewModel.selectedSourceIDs.isEmpty)
            }

            HStack {
                Button("Remove") { viewModel.removeSelected() }
                    .disabled(viewModel.selectedSourceIDs.isEmpty)
                Button("Clear") { viewModel.clearSources() }
                    .disabled(viewModel.sources.isEmpty)
                Spacer()
            }
        }
    }

    private var settingsPanel: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                HStack(alignment: .center) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Build Settings")
                            .font(.title2.bold())
                        Text("Configure the mod pack installer and output.")
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    if viewModel.isBuilding {
                        ProgressView()
                            .controlSize(.small)
                    }
                    Button(action: viewModel.build) {
                        Text(viewModel.isBuilding ? "Building..." : "Build Mods")
                            .fontWeight(.semibold)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(!viewModel.canBuild)
                }

                GroupBox("Target") {
                    Grid(alignment: .leadingFirstTextBaseline, horizontalSpacing: 12, verticalSpacing: 12) {
                        GridRow {
                            Text("Region")
                            Picker("Region", selection: $viewModel.region) {
                                ForEach(Region.allCases) { region in
                                    Text(region.displayName).tag(region)
                                }
                            }
                            .pickerStyle(.menu)
                        }
                        GridRow {
                            Text("Game root / install folder")
                            HStack {
                                TextField("Game root", text: $viewModel.gameRoot)
                                Button("Browse") { viewModel.browseGameRoot() }
                            }
                        }
                        GridRow {
                            Text("Mods folder")
                            TextField("mods", text: $viewModel.modsFolderName)
                        }
                        GridRow {
                            Text("Version folder")
                            TextField("2.2.0.2", text: $viewModel.versionFolder)
                        }
                        GridRow {
                            Text("Recommended install path")
                            Text(viewModel.recommendedInstallPath)
                                .font(.system(.body, design: .monospaced))
                                .textSelection(.enabled)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }

                GroupBox("Installer") {
                    Grid(alignment: .leadingFirstTextBaseline, horizontalSpacing: 12, verticalSpacing: 12) {
                        GridRow {
                            Text("Installer file name")
                            TextField("Installer file name", text: $viewModel.installerName)
                        }
                        GridRow {
                            Text("Setup window title")
                            TextField("Setup window title", text: $viewModel.setupWindowTitle)
                        }
                        GridRow {
                            Text("Installer icon")
                            VStack(alignment: .leading, spacing: 8) {
                                Text(viewModel.installerIconPath.isEmpty ? "No icon selected" : viewModel.installerIconPath)
                                    .font(.system(.body, design: .monospaced))
                                    .foregroundStyle(viewModel.installerIconPath.isEmpty ? .secondary : .primary)
                                    .textSelection(.enabled)
                                HStack {
                                    Button("Browse") { viewModel.browseInstallerIcon() }
                                    Button("Clear") { viewModel.clearInstallerIcon() }
                                        .disabled(viewModel.installerIconPath.isEmpty)
                                }
                            }
                        }
                        GridRow {
                            Text("Icon preview")
                            HStack(spacing: 12) {
                                ZStack {
                                    RoundedRectangle(cornerRadius: 10)
                                        .fill(Color.secondary.opacity(0.08))
                                    if let image = viewModel.installerIconPreview {
                                        Image(nsImage: image)
                                            .resizable()
                                            .scaledToFit()
                                            .padding(8)
                                    } else {
                                        Text("No icon")
                                            .foregroundStyle(.secondary)
                                    }
                                }
                                .frame(width: 64, height: 64)
                                Text("PNG, JPG, GIF, BMP, ICO and other image formats are converted to .ico for the Windows installer.")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        GridRow {
                            Text("Installer EXE preview")
                            Text(viewModel.previewInstallerFileName)
                                .font(.system(.body, design: .monospaced))
                                .textSelection(.enabled)
                        }
                        GridRow {
                            Text("Setup title preview")
                            Text(viewModel.previewSetupWindowTitle)
                                .textSelection(.enabled)
                        }
                        GridRow {
                            Text("Export")
                            VStack(alignment: .leading, spacing: 8) {
                                Toggle("Create ZIP package", isOn: $viewModel.createZip)
                                Toggle("Export Windows EXE installer", isOn: $viewModel.createInstallerExe)
                            }
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }

                GroupBox("Output") {
                    Grid(alignment: .leadingFirstTextBaseline, horizontalSpacing: 12, verticalSpacing: 12) {
                        GridRow {
                            Text("Output directory")
                            VStack(alignment: .leading, spacing: 8) {
                                Text(viewModel.outputDirectory)
                                    .font(.system(.body, design: .monospaced))
                                    .textSelection(.enabled)
                                HStack {
                                    Button("Browse") { viewModel.browseOutputDirectory() }
                                    Button("Reveal") { viewModel.revealOutputDirectory() }
                                }
                            }
                        }
                        if let result = viewModel.lastBuildResult {
                            GridRow {
                                Text("Last build folder")
                                Text(result.buildFolder.path)
                                    .font(.system(.body, design: .monospaced))
                                    .textSelection(.enabled)
                            }
                            if let zipURL = result.zipPath {
                                GridRow {
                                    Text("Last ZIP")
                                    Text(zipURL.path)
                                        .font(.system(.body, design: .monospaced))
                                        .textSelection(.enabled)
                                }
                            }
                            if let exeURL = result.installerExePath {
                                GridRow {
                                    Text("Last EXE")
                                    Text(exeURL.path)
                                        .font(.system(.body, design: .monospaced))
                                        .textSelection(.enabled)
                                }
                            }
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var logPanel: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Log")
                    .font(.headline)
                Spacer()
            }
            TextEditor(text: .constant(viewModel.logText))
                .font(.system(.caption, design: .monospaced))
                .scrollContentBackground(.hidden)
                .background(Color.black.opacity(0.03))
        }
    }

    private var errorAlertBinding: Binding<Bool> {
        Binding(
            get: { viewModel.errorMessage != nil },
            set: { if !$0 { viewModel.errorMessage = nil } }
        )
    }

    private var successAlertBinding: Binding<Bool> {
        Binding(
            get: { viewModel.successMessage != nil },
            set: { if !$0 { viewModel.successMessage = nil } }
        )
    }
}
