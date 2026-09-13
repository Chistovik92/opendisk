import Foundation

/// Текст на двух языках интерфейса.
struct Localized: Decodable, Hashable {
    let ru: String
    let en: String

    func pick(_ russian: Bool) -> String { russian ? ru : en }
}

struct CatalogField: Decodable, Identifiable, Hashable {
    let key: String
    let label: Localized
    let isPassword: Bool
    let required: Bool
    let help: Localized?

    var id: String { key }
}

struct CatalogGroup: Decodable, Hashable {
    let key: String
    let title: Localized
}

/// Сервис, к которому можно подключиться.
struct CatalogService: Decodable, Identifiable, Hashable {
    let id: String
    let title: Localized
    let subtitle: Localized
    let backend: String
    let fixed: [String: String]
    let fields: [CatalogField]
    let oauth: Bool
    let hint: Localized?
    let accent: Int64
    let glyph: String
    let group: String
    let keywords: [String]

    func matches(_ query: String) -> Bool {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return true }
        return ([title.ru, title.en, subtitle.ru, subtitle.en, backend] + keywords)
            .contains { $0.localizedCaseInsensitiveContains(q) }
    }
}

/// Каталог сервисов — тот же, что на компьютере и Android.
///
/// Файл `catalog.json` выгружает сборка из `rclone-bridge/.../CloudCatalog.kt`
/// (`./gradlew :rclone-bridge:exportCatalog`): Kotlin на iOS не работает, а
/// переписанная вручную копия расходилась бы с оригиналом. Здесь только
/// чтение файла и построение полного списка из `config/providers` —
/// повторение `CloudCatalog.fromProviders`.
struct Catalog: Decodable {
    let groups: [CatalogGroup]
    let services: [CatalogService]
    let notClouds: [String]
    let commonOptionalFields: [String]

    static let shared: Catalog = {
        guard let url = Bundle.main.url(forResource: "catalog", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let catalog = try? JSONDecoder().decode(Catalog.self, from: data)
        else {
            // Без каталога приложение всё равно работает: остаётся полный
            // список rclone. Упасть из-за отсутствующего файла было бы хуже.
            return Catalog(groups: [], services: [], notClouds: [], commonOptionalFields: [])
        }
        return catalog
    }()

    func title(ofGroup key: String) -> Localized {
        groups.first { $0.key == key }?.title ?? Localized(ru: "Все сервисы rclone", en: "All rclone services")
    }

    /// Всё, что знает встроенный rclone. У S3 каждый провайдер — отдельная
    /// строка: человек ищет свой сервис по названию.
    func fromProviders(_ providers: [Provider]) -> [CatalogService] {
        let curated = Set(services.map { "\($0.backend)|\($0.fixed["provider"] ?? "")" })
        return providers
            .filter { !notClouds.contains($0.type) }
            .flatMap { provider -> [CatalogService] in
                let variants = provider.Options
                    .first { $0.Name == "provider" && !($0.Examples ?? []).isEmpty }?
                    .Examples?
                    .filter { !$0.Value.isEmpty } ?? []
                if variants.isEmpty {
                    return [generic(provider, variant: nil, description: provider.Description)]
                }
                return variants.map {
                    generic(provider, variant: $0.Value, description: String($0.Help.split(separator: "\n").first ?? ""))
                }
            }
            .filter { !curated.contains("\($0.backend)|\($0.fixed["provider"] ?? "")") }
            .sorted { $0.title.en.lowercased() < $1.title.en.lowercased() }
    }

    private func generic(_ provider: Provider, variant: String?, description: String) -> CatalogService {
        let options = provider.Options
            .filter { !$0.Advanced && ($0.Required || commonOptionalFields.contains($0.Name)) }
            .filter { !(variant != nil && $0.Name == "provider") }
            .filter { $0.applies(to: variant) }
        var seen = Set<String>()
        let unique = options.filter { seen.insert($0.Name).inserted }
        let names = Set(provider.Options.map(\.Name))
        // Признак браузерного входа — поле токена рядом с идентификатором
        // приложения и отсутствие обязательного пароля (у Mail.ru токен есть,
        // но входят туда паролем).
        let oauth = names.contains("token") && names.contains("client_id")
            && !unique.contains { $0.Required && $0.IsPassword }
        let title = description.isEmpty ? provider.Name : description
        let subtitle = [provider.type, variant].compactMap { $0 }.joined(separator: " · ")

        return CatalogService(
            id: "rclone:\(provider.type)" + (variant.map { ":\($0)" } ?? ""),
            title: Localized(ru: title, en: title),
            subtitle: Localized(ru: subtitle, en: subtitle),
            backend: provider.type,
            fixed: variant.map { ["provider": $0] } ?? [:],
            fields: unique
                .filter { !(oauth && ["client_id", "client_secret"].contains($0.Name)) || $0.Required }
                .map { option in
                    let help = option.Help.split(separator: "\n").first.map(String.init) ?? ""
                    return CatalogField(
                        key: option.Name,
                        label: Localized(ru: option.Name, en: option.Name),
                        isPassword: option.IsPassword,
                        required: option.Required,
                        help: help.isEmpty ? nil : Localized(ru: help, en: help)
                    )
                },
            oauth: oauth,
            hint: nil,
            accent: 0xFF607D8B,
            glyph: String(provider.type.prefix(2)).capitalized,
            group: "ALL",
            keywords: [provider.Name, provider.type, variant].compactMap { $0 }
        )
    }
}
