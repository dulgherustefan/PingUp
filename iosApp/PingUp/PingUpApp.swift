import PingUpUi
import SwiftUI

@main
struct PingUpApp: App {
    @UIApplicationDelegateAdaptor private var delegate: AppDelegate

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

final class AppDelegate: NSObject, UIApplicationDelegate {
    // portrait, and landscape while the phone is the control console
    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        UIInterfaceOrientationMask(rawValue: UInt(Orientations.shared.mask))
    }
}
