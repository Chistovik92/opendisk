import SwiftUI

@main
struct OpenDiskApp: App {

    @AppStorage("theme") private var theme = AppTheme.auto.rawValue
    @AppStorage("language") private var language = AppLanguage.auto.rawValue

    var body: some Scene {
        WindowGroup {
            CloudListView(strings: Strings.of(AppLanguage(rawValue: language) ?? .auto))
                // Тема — из системы, пока человек не попросил иначе: nil
                // означает «как на телефоне», включая переключение по
                // расписанию.
                .preferredColorScheme(colorScheme)
        }
    }

    private var colorScheme: ColorScheme? {
        switch AppTheme(rawValue: theme) ?? .auto {
        case .auto: return nil
        case .light: return .light
        case .dark: return .dark
        }
    }
}

/// Одно облако в списке.
struct Cloud: Identifiable {
    let name: String
    var about: AboutInfo?
    var supportsLinks = false

    var id: String { name }
}

/// Состояние списка облаков и работа с rclone.
@MainActor
final class CloudsModel: ObservableObject {

    @Published var clouds: [Cloud] = []
    @Published var starting = true
    @Published var error: String?
    @Published var rcloneVersion: String?

    func reload() async {
        do {
            let names = try await Rclone.shared.remotes()
            clouds = names.map { name in
                clouds.first { $0.name == name } ?? Cloud(name: name)
            }
            error = nil
            starting = false
            rcloneVersion = try? await Rclone.shared.version()

            // Место и умение выдавать ссылки спрашиваем по одному и молча
            // проглатываем отказы: часть бэкендов этого не умеет, и общий
            // список не должен из-за них оставаться пустым.
            for name in names {
                if let about = try? await Rclone.shared.about(name) {
                    update(name) { $0.about = about }
                }
                if let info = try? await Rclone.shared.fsInfo(name) {
                    update(name) { $0.supportsLinks = info.supportsPublicLink }
                }
            }
        } catch {
            starting = false
            self.error = error.localizedDescription
        }
    }

    func add(name: String, type: String, parameters: [String: String], secrets: Set<String>) async -> String? {
        do {
            var prepared: [String: String] = [:]
            for (key, value) in parameters where !value.trimmingCharacters(in: .whitespaces).isEmpty {
                let trimmed = value.trimmingCharacters(in: .whitespaces)
                prepared[key] = secrets.contains(key) ? try await Rclone.shared.obscure(trimmed) : trimmed
            }
            try await Rclone.shared.createRemote(name: name, type: type, parameters: prepared)
            await reload()
            return nil
        } catch {
            return error.localizedDescription
        }
    }

    func delete(_ name: String) async {
        do {
            try await Rclone.shared.deleteRemote(name)
        } catch {
            self.error = error.localizedDescription
        }
        await reload()
    }

    private func update(_ name: String, _ transform: (inout Cloud) -> Void) {
        guard let index = clouds.firstIndex(where: { $0.name == name }) else { return }
        transform(&clouds[index])
    }
}
