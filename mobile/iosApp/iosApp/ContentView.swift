import SwiftUI
import UIKit
import Shared

/// Where the app finds the backend. The iOS simulator shares the Mac's network, so localhost works.
/// A real iPhone: use the Mac's address on the local network, for example "http://192.168.1.20:8080/".
private let backendUrl = "http://localhost:8080/"

/// Hosts the shared Compose UI. All screens and logic come from the Kotlin `Shared` framework.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.0"
        return MainViewControllerKt.MainViewController(baseUrl: backendUrl, appVersion: version)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea()   // Compose draws edge to edge and applies the safe area itself
    }
}
