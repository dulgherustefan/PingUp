import PingUpUi
import SwiftUI

@main
struct PingUpApp: App {
    var body: some Scene {
        WindowGroup {
            // Compose handles the safe area itself, as the Android app does with edge-to-edge
            SharedScreens().ignoresSafeArea()
        }
    }
}

/** The screens from :ui, in one view controller. */
struct SharedScreens: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}
