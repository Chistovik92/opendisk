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

    /// Все сервисы, которые знает встроенный rclone, — нижняя часть списка.
    @Published var allServices: [CatalogService] = []
    /// Идёт вход через браузер; nil — не идёт.
    @Published var signIn: SignIn?

    struct SignIn: Equatable {
        let cloud: String
        /// Ссылка подтверждения; nil — rclone её ещё не напечатал.
        var link: String?
        /// Человек нажал «Отмена»: ошибку «access_denied» показывать не нужно.
        var cancelled = false
    }

    func loadAllServices() async {
        guard allServices.isEmpty, let providers = try? await Rclone.shared.providers() else { return }
        allServices = Catalog.shared.fromProviders(providers)
    }

    /// Добавляет облако.
    ///
    /// Для сервисов со входом через браузер запрос `config/create` висит, пока
    /// человек подтверждает доступ: rclone внутри приложения поднимает сервер
    /// подтверждения и печатает ссылку. Ссылка перехватывается из его журнала
    /// и кладётся в `signIn` — форма открывает по ней окно входа Apple.
    func add(service: CatalogService, name: String, values: [String: String]) async -> String? {
        let existedBefore = clouds.contains { $0.name == name }
        let secrets = Set(service.fields.filter(\.isPassword).map(\.key))
        var listener: UUID?

        if service.oauth {
            // Ссылки прошлых попыток не наши: их сервер уже закрыт.
            let stale = Set(RcloneLog.shared.recentLines().compactMap { OAuthLink.find(in: $0) })
            signIn = SignIn(cloud: name)
            listener = RcloneLog.shared.addListener { [weak self] line in
                guard let link = OAuthLink.find(in: line), !stale.contains(link) else { return }
                Task { @MainActor in
                    guard let self, var current = self.signIn, current.link == nil else { return }
                    current.link = link
                    self.signIn = current
                    // «Отмену» могли нажать раньше, чем появилась ссылка.
                    if current.cancelled { OAuthLink.cancel(link) }
                }
            }
        }
        defer { if let listener { RcloneLog.shared.removeListener(listener) } }

        do {
            var prepared: [String: String] = [:]
            for (key, value) in service.fixed.merging(values, uniquingKeysWith: { $1 })
            where !value.trimmingCharacters(in: .whitespaces).isEmpty {
                let trimmed = value.trimmingCharacters(in: .whitespaces)
                prepared[key] = secrets.contains(key) ? try await Rclone.shared.obscure(trimmed) : trimmed
            }
            try await Rclone.shared.createRemote(name: name, type: service.backend, parameters: prepared)
            signIn = nil
            await reload()
            return nil
        } catch {
            let cancelled = signIn?.cancelled == true
            signIn = nil
            // rclone записывает облако в конфиг до входа в браузер: без уборки
            // после отказа в списке осталось бы облако без токена.
            if !existedBefore {
                try? await Rclone.shared.deleteRemote(name)
                await reload()
            }
            return cancelled ? nil : error.localizedDescription
        }
    }

    /// Прерывает вход через браузер: будит висящий `config/create` отказом
    /// по той же ссылке — ровно так, как ответил бы сервис.
    func cancelSignIn() {
        guard var current = signIn else { return }
        current.cancelled = true
        signIn = current
        if let link = current.link { OAuthLink.cancel(link) }
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
