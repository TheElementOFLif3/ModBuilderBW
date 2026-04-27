import Foundation

enum Region: String, Codable, CaseIterable, Identifiable {
    case EU
    case NA
    case RU
    case CUSTOM
    case NONE

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .NONE:
            return "Auto Detection"
        default:
            return rawValue
        }
    }

    var installerToken: String {
        switch self {
        case .NONE:
            return "AUTO"
        default:
            return rawValue
        }
    }
}

struct BuildRequest: Codable {
    var sources: [String]
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
}
