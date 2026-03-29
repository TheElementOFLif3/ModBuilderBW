import Foundation

struct SourceEntry: Identifiable, Codable, Hashable {
    let id: UUID
    var path: String
    var included: Bool

    init(id: UUID = UUID(), path: String, included: Bool = true) {
        self.id = id
        self.path = SourceEntry.normalize(path)
        self.included = included
    }

    var url: URL {
        URL(fileURLWithPath: path)
    }

    var name: String {
        url.lastPathComponent
    }

    var displayLine: String {
        "[\(included ? "ON" : "OFF")] \(path)"
    }

    private static func normalize(_ path: String) -> String {
        URL(fileURLWithPath: path).standardizedFileURL.path
    }
}
