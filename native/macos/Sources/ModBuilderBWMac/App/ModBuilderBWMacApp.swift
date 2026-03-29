import SwiftUI

@main
struct ModBuilderBWMacApp: App {
    @StateObject private var viewModel = BuildRequestViewModel()

    var body: some Scene {
        WindowGroup("Mod Builder BW") {
            ContentView(viewModel: viewModel)
                .frame(minWidth: 1200, minHeight: 820)
        }
        .windowResizability(.contentSize)
        .commands {
            CommandGroup(replacing: .newItem) { }
        }
    }
}
