import AuthenticationServices
import SwiftUI

/// Добавление облака: весь каталог сервисов с поиском.
///
/// Сверху — отобранные вручную сервисы по разделам, снизу — всё, что знает
/// встроенный rclone. До 0.5.4 здесь было пять сервисов и все по паролю.
struct AddCloudView: View {

    let strings: Strings
    @ObservedObject var model: CloudsModel

    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var chosen: CatalogService?

    private var sections: [(key: String, services: [CatalogService])] {
        let all = (Catalog.shared.services + model.allServices).filter { $0.matches(query) }
        let order = Catalog.shared.groups.map(\.key) + ["ALL"]
        return order.compactMap { key in
            let inGroup = all.filter { $0.group == key }
            return inGroup.isEmpty ? nil : (key, inGroup)
        }
    }

    var body: some View {
        NavigationView {
            List {
                ForEach(sections, id: \.key) { section in
                    Section(Catalog.shared.title(ofGroup: section.key).pick(strings.russian)) {
                        ForEach(section.services) { service in
                            Button {
                                chosen = service
                            } label: {
                                ServiceRow(service: service, strings: strings)
                            }
                        }
                    }
                }
                if model.allServices.isEmpty {
                    HStack(spacing: 10) {
                        ProgressView()
                        Text(strings.loadingAllServices).foregroundColor(.secondary)
                    }
                } else if sections.isEmpty {
                    Text(strings.nothingFound).foregroundColor(.secondary)
                }
            }
            .searchable(text: $query, prompt: strings.searchServices)
            .navigationTitle(strings.chooseService)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button(strings.cancel) { dismiss() }
                }
            }
            .sheet(item: $chosen) { service in
                ServiceForm(strings: strings, service: service, model: model) {
                    chosen = nil
                    dismiss()
                }
            }
        }
        .navigationViewStyle(.stack)
        .task { await model.loadAllServices() }
    }
}

private struct ServiceRow: View {
    let service: CatalogService
    let strings: Strings

    var body: some View {
        HStack(spacing: 12) {
            // Буква на цветном квадрате, а не логотип сервиса: чужие товарные
            // знаки в приложение мы не кладём.
            Text(service.glyph)
                .font(.subheadline.bold())
                .foregroundColor(.white)
                .frame(width: 36, height: 36)
                .background(Color(argb: service.accent))
                .clipShape(RoundedRectangle(cornerRadius: 8))
            VStack(alignment: .leading, spacing: 2) {
                Text(service.title.pick(strings.russian)).foregroundColor(.primary)
                Text(service.subtitle.pick(strings.russian))
                    .font(.footnote)
                    .foregroundColor(.secondary)
                    .lineLimit(1)
            }
        }
    }
}

private struct ServiceForm: View {

    let strings: Strings
    let service: CatalogService
    @ObservedObject var model: CloudsModel
    let onAdded: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    @State private var values: [String: String] = [:]
    @State private var error: String?
    @State private var busy = false
    @StateObject private var webSignIn = WebSignIn()

    init(strings: Strings, service: CatalogService, model: CloudsModel, onAdded: @escaping () -> Void) {
        self.strings = strings
        self.service = service
        self.model = model
        self.onAdded = onAdded
        // «rclone:s3:Wasabi» превращается в «Wasabi».
        let base = service.id.split(separator: ":").last.map(String.init) ?? service.id
        var candidate = base
        var index = 2
        let taken = Set(model.clouds.map(\.name))
        while taken.contains(candidate) {
            candidate = "\(base)\(index)"
            index += 1
        }
        _name = State(initialValue: candidate)
    }

    private var filled: Bool {
        !name.trimmingCharacters(in: .whitespaces).isEmpty &&
            !model.clouds.contains { $0.name == name.trimmingCharacters(in: .whitespaces) } &&
            service.fields.filter(\.required).allSatisfy { !(values[$0.key] ?? "").isEmpty }
    }

    var body: some View {
        NavigationView {
            Form {
                if let hint = service.hint {
                    Section { Text(hint.pick(strings.russian)).font(.footnote).foregroundColor(.secondary) }
                }
                Section {
                    TextField(strings.name, text: $name)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    ForEach(service.fields) { field in
                        VStack(alignment: .leading, spacing: 4) {
                            let label = field.label.pick(strings.russian) + (field.required ? " *" : "")
                            if field.isPassword {
                                // Под маской: на экране телефона, который видно
                                // из-за плеча, пароль открытым текстом недопустим.
                                SecureField(label, text: binding(field.key))
                            } else {
                                TextField(label, text: binding(field.key))
                                    .autocorrectionDisabled()
                                    .textInputAutocapitalization(.never)
                            }
                            if let help = field.help {
                                Text(help.pick(strings.russian)).font(.caption).foregroundColor(.secondary)
                            }
                        }
                    }
                }
                if service.oauth {
                    Section { Text(strings.browserWillOpen).font(.footnote).foregroundColor(.secondary) }
                }
                if let signIn = model.signIn {
                    Section {
                        HStack(spacing: 10) {
                            ProgressView()
                            Text(signIn.link == nil ? strings.preparingSignIn : strings.waitingForBrowser)
                        }
                        if let link = signIn.link {
                            Button(strings.openBrowserAgain) { webSignIn.start(link) { model.cancelSignIn() } }
                        }
                        Button(strings.cancel, role: .destructive) { model.cancelSignIn() }
                            .disabled(signIn.cancelled)
                    }
                }
                if let error {
                    Section { Text(error).foregroundColor(.red) }
                }
                Section {
                    Button(busy ? strings.adding : (service.oauth ? strings.signInWithBrowser : strings.add)) { add() }
                        .disabled(busy || !filled)
                }
            }
            .navigationTitle(service.title.pick(strings.russian))
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button(strings.back) { dismiss() }.disabled(busy)
                }
            }
        }
        .navigationViewStyle(.stack)
        // Окно входа открывается, как только rclone напечатал ссылку, и
        // закрывается, как только вход закончился — удачно или нет.
        .onChange(of: model.signIn?.link) { link in
            if let link { webSignIn.start(link) { model.cancelSignIn() } }
        }
        .onChange(of: model.signIn == nil) { finished in
            if finished { webSignIn.finish() }
        }
    }

    private func binding(_ key: String) -> Binding<String> {
        Binding(get: { values[key] ?? "" }, set: { values[key] = $0 })
    }

    private func add() {
        busy = true
        error = nil
        Task {
            let failure = await model.add(
                service: service,
                name: name.trimmingCharacters(in: .whitespaces),
                values: values
            )
            busy = false
            if let failure {
                error = failure
            } else if model.clouds.contains(where: { $0.name == name.trimmingCharacters(in: .whitespaces) }) {
                onAdded()
            }
        }
    }
}

/// Окно входа Apple поверх приложения.
///
/// Не встроенный браузер: Google отказывается пускать на вход из встроенных
/// окон, и правильно — встроенное окно видит пароль. У окна Apple есть и
/// второе достоинство, без которого вход бы не работал: приложение остаётся
/// активным, пока оно открыто, а значит, работает и сервер подтверждения,
/// который rclone поднял внутри приложения. Свёрнутое приложение iOS
/// усыпила бы через несколько секунд — вместе с сервером.
///
/// Адрес возврата у rclone — `http://127.0.0.1:53682/`, а не своя схема
/// приложения, поэтому окно само не закрывается: закрываем его, когда
/// rclone сообщил, что вход закончен.
final class WebSignIn: NSObject, ObservableObject, ASWebAuthenticationPresentationContextProviding {

    private var session: ASWebAuthenticationSession?
    private var finishing = false

    func start(_ link: String, onUserCancel: @escaping () -> Void) {
        guard let url = URL(string: link) else { return }
        finish()
        finishing = false
        let session = ASWebAuthenticationSession(url: url, callbackURLScheme: nil) { [weak self] _, error in
            guard let self, !self.finishing else { return }
            if let error = error as? ASWebAuthenticationSessionError, error.code == .canceledLogin {
                DispatchQueue.main.async { onUserCancel() }
            }
        }
        // Не частный режим: человек уже может быть вошедшим в свой аккаунт,
        // и выбрать его — быстрее, чем набирать пароль заново.
        session.prefersEphemeralWebBrowserSession = false
        session.presentationContextProvider = self
        self.session = session
        session.start()
    }

    func finish() {
        finishing = true
        session?.cancel()
        session = nil
    }

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first { $0.isKeyWindow } ?? ASPresentationAnchor()
    }
}

extension Color {
    /// Цвет из числа 0xAARRGGBB — в таком виде он лежит в каталоге.
    init(argb: Int64) {
        self.init(
            .sRGB,
            red: Double((argb >> 16) & 0xFF) / 255,
            green: Double((argb >> 8) & 0xFF) / 255,
            blue: Double(argb & 0xFF) / 255,
            opacity: Double((argb >> 24) & 0xFF) / 255
        )
    }
}
