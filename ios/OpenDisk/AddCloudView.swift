import SwiftUI

/// Поле, которое спрашиваем при добавлении облака.
struct PresetField: Identifiable {
    let key: String
    let label: String
    var isPassword = false
    var required = true

    var id: String { key }
}

/// Готовое подключение к сервису — то же, что плитки на компьютере и на
/// Android. Сервисы с подтверждением доступа в браузере сюда не попали:
/// их поток должен вернуть человека из браузера обратно в приложение, а это
/// отдельная работа.
struct Preset: Identifiable {
    let id: String
    let title: String
    let subtitle: String
    let backend: String
    var fixed: [String: String] = [:]
    let fields: [PresetField]
    var hint: String?
}

func presets(_ strings: Strings) -> [Preset] {
    [
        Preset(
            id: "yandex",
            title: strings.yandexDisk,
            subtitle: strings.appPassword,
            backend: "webdav",
            fixed: ["url": "https://webdav.yandex.ru", "vendor": "other"],
            fields: [
                PresetField(key: "user", label: strings.login),
                PresetField(key: "pass", label: strings.appPassword, isPassword: true),
            ],
            hint: strings.yandexHint
        ),
        Preset(
            id: "mailru",
            title: strings.mailru,
            subtitle: strings.appPassword,
            backend: "mailru",
            fields: [
                PresetField(key: "user", label: strings.login),
                PresetField(key: "pass", label: strings.appPassword, isPassword: true),
            ],
            hint: strings.mailruHint
        ),
        Preset(
            id: "webdav",
            title: "WebDAV",
            subtitle: strings.anyWebdav,
            backend: "webdav",
            fields: [
                PresetField(key: "url", label: strings.serverUrl),
                PresetField(key: "user", label: strings.login, required: false),
                PresetField(key: "pass", label: strings.password, isPassword: true, required: false),
            ]
        ),
        Preset(
            id: "sftp",
            title: "SFTP",
            subtitle: strings.sshAccess,
            backend: "sftp",
            fields: [
                PresetField(key: "host", label: strings.host),
                PresetField(key: "user", label: strings.login, required: false),
                PresetField(key: "pass", label: strings.password, isPassword: true, required: false),
            ]
        ),
        Preset(
            id: "ftp",
            title: "FTP",
            subtitle: strings.ftpServer,
            backend: "ftp",
            fields: [
                PresetField(key: "host", label: strings.host),
                PresetField(key: "user", label: strings.login, required: false),
                PresetField(key: "pass", label: strings.password, isPassword: true, required: false),
            ]
        ),
    ]
}

struct AddCloudView: View {

    let strings: Strings
    @ObservedObject var model: CloudsModel

    @Environment(\.dismiss) private var dismiss
    @State private var chosen: Preset?

    var body: some View {
        NavigationView {
            Group {
                if let preset = chosen {
                    PresetForm(strings: strings, preset: preset, model: model) { dismiss() }
                } else {
                    List {
                        Section(strings.chooseService) {
                            ForEach(presets(strings)) { preset in
                                Button {
                                    chosen = preset
                                } label: {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(preset.title).foregroundColor(.primary)
                                        Text(preset.subtitle)
                                            .font(.footnote)
                                            .foregroundColor(.secondary)
                                    }
                                }
                            }
                        }
                        Section {
                            Text(strings.browserServicesMissing)
                                .font(.footnote)
                                .foregroundColor(.secondary)
                        }
                    }
                }
            }
            .navigationTitle(chosen?.title ?? strings.chooseService)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button(chosen == nil ? strings.cancel : strings.back) {
                        if chosen == nil { dismiss() } else { chosen = nil }
                    }
                }
            }
        }
        .navigationViewStyle(.stack)
    }
}

private struct PresetForm: View {

    let strings: Strings
    let preset: Preset
    @ObservedObject var model: CloudsModel
    let onAdded: () -> Void

    @State private var name: String
    @State private var values: [String: String] = [:]
    @State private var error: String?
    @State private var busy = false

    init(strings: Strings, preset: Preset, model: CloudsModel, onAdded: @escaping () -> Void) {
        self.strings = strings
        self.preset = preset
        self.model = model
        self.onAdded = onAdded
        _name = State(initialValue: preset.id)
    }

    private var filled: Bool {
        !name.trimmingCharacters(in: .whitespaces).isEmpty &&
            preset.fields.filter(\.required).allSatisfy { !(values[$0.key] ?? "").isEmpty }
    }

    var body: some View {
        Form {
            if let hint = preset.hint {
                Section { Text(hint).font(.footnote).foregroundColor(.secondary) }
            }
            Section {
                TextField(strings.name, text: $name)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                ForEach(preset.fields) { field in
                    if field.isPassword {
                        // Под маской: на экране телефона, который видно
                        // из-за плеча, пароль открытым текстом недопустим.
                        SecureField(field.label, text: binding(field.key))
                    } else {
                        TextField(field.label, text: binding(field.key))
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                    }
                }
            }
            if let error {
                Section { Text(error).foregroundColor(.red) }
            }
            Section {
                Button(busy ? strings.adding : strings.add) { add() }
                    .disabled(busy || !filled)
            }
        }
    }

    private func binding(_ key: String) -> Binding<String> {
        Binding(get: { values[key] ?? "" }, set: { values[key] = $0 })
    }

    private func add() {
        busy = true
        error = nil
        let secrets = Set(preset.fields.filter(\.isPassword).map(\.key))
        var parameters = preset.fixed
        for (key, value) in values { parameters[key] = value }

        Task {
            let failure = await model.add(
                name: name.trimmingCharacters(in: .whitespaces),
                type: preset.backend,
                parameters: parameters,
                secrets: secrets
            )
            busy = false
            if let failure {
                error = failure
            } else {
                onAdded()
            }
        }
    }
}
