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
    // the session and the mesh start with the process, also when iOS starts it in the background for Bluetooth: then
    // the Bluetooth managers it restores come back first, as Apple asks
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        _ = MainViewControllerKt.startApp(
            restoreCentral: launchOptions?[.bluetoothCentrals] != nil,
            restoreServer: launchOptions?[.bluetoothPeripherals] != nil
        )
        return true
    }

    // portrait, and landscape while the phone is the control console
    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        UIInterfaceOrientationMask(rawValue: UInt(Orientations.shared.mask))
    }

    // a quick action from the app's icon comes with the scene when it launches the app, and to the scene's delegate after
    func application(
        _ application: UIApplication,
        configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        if let item = options.shortcutItem {
            _ = MainViewControllerKt.startApp(restoreCentral: false, restoreServer: false).shortcut(type: item.type)
        }
        let configuration = UISceneConfiguration(name: nil, sessionRole: connectingSceneSession.role)
        configuration.delegateClass = SceneDelegate.self
        return configuration
    }
}

/** The window's own events: a quick action chosen from the icon while the app runs. */
final class SceneDelegate: NSObject, UIWindowSceneDelegate {
    func windowScene(_ windowScene: UIWindowScene, performActionFor shortcutItem: UIApplicationShortcutItem) async -> Bool {
        MainViewControllerKt.startApp(restoreCentral: false, restoreServer: false).shortcut(type: shortcutItem.type)
    }
}
