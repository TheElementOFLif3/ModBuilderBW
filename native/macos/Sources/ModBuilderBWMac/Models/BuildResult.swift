import Foundation

struct BuildResult {
    let buildFolder: URL
    let zipPath: URL?
    let installerExePath: URL?
    let previewPath: String
    let copiedItems: Int
}
