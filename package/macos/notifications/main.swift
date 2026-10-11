import AppKit
import Foundation
import UserNotifications

// This development-only app owns notifications for a JVM started outside korTTY.app.
// stdin carries JSON requests; stdout carries only identifiers, never notification text.
struct NotificationMessage: Decodable, Sendable {
    let identifier: String
    let title: String
    let body: String
}

final class NotificationDelegate: NSObject, UNUserNotificationCenterDelegate {
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .list])
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        if response.actionIdentifier == UNNotificationDefaultActionIdentifier {
            let identifier = response.notification.request.identifier
            let parentPID = getppid()
            Task { @MainActor in
                if let parent = NSRunningApplication(processIdentifier: parentPID) {
                    if #available(macOS 14.0, *) {
                        NSApplication.shared.yieldActivation(to: parent)
                    }
                    parent.activate(options: [])
                }
                FileHandle.standardOutput.write(Data("activate:\(identifier)\n".utf8))
            }
        }
        completionHandler()
    }
}

@MainActor
final class NotificationReceiver {
    private let center = UNUserNotificationCenter.current()
    private let delegate = NotificationDelegate()
    private var authorization: Task<Bool, Never>?
    private var pendingCount = 0

    init() {
        center.delegate = delegate
    }

    func receive(_ message: NotificationMessage) async {
        guard pendingCount < 64 else { return }
        pendingCount += 1
        defer { pendingCount -= 1 }
        if authorization == nil {
            authorization = Task {
                do {
                    return try await center.requestAuthorization(options: [.alert, .sound])
                } catch {
                    let failure = error as NSError
                    FileHandle.standardError.write(Data("Notification authorization failed: \(failure.domain) \(failure.code) \(failure.localizedDescription)\n".utf8))
                    return false
                }
            }
        }
        guard let authorization, await authorization.value else {
            return
        }
        let content = UNMutableNotificationContent()
        content.title = message.title
        content.body = message.body
        let request = UNNotificationRequest(identifier: message.identifier, content: content, trigger: nil)
        do {
            try await center.add(request)
        } catch {
            FileHandle.standardError.write(Data("Posting a notification failed\n".utf8))
        }
    }
}

let app = NSApplication.shared
app.setActivationPolicy(.accessory)
let receiver = NotificationReceiver()

// readLine blocks, so keep pipe I/O off the main actor. EOF means korTTY has exited.
Task.detached {
    while let line = readLine() {
        do {
            let message = try JSONDecoder().decode(NotificationMessage.self, from: Data(line.utf8))
            Task { await receiver.receive(message) }
        } catch {
            FileHandle.standardError.write(Data("Invalid notification request\n".utf8))
        }
    }
    await MainActor.run { app.terminate(nil) }
}
app.run()
