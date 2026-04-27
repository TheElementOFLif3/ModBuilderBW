import Foundation

struct BuildResult {
    let buildFolder: URL
    let zipPath: URL?
    let installerMsiPath: URL?
    let previewPath: String
    let copiedItems: Int
}
