import SwiftUI

/// Настройки: оформление, язык и сведения о приложении.
struct SettingsView: View {

    let strings: Strings
    let rcloneVersion: String?

    @Environment(\.dismiss) private var dismiss
    @AppStorage("theme") private var theme = AppTheme.auto.rawValue
    @AppStorage("language") private var language = AppLanguage.auto.rawValue

    var body: some View {
        NavigationView {
            Form {
                Section(strings.theme) {
                    Picker(strings.theme, selection: $theme) {
                        Text(strings.themeAuto).tag(AppTheme.auto.rawValue)
                        Text(strings.themeLight).tag(AppTheme.light.rawValue)
                        Text(strings.themeDark).tag(AppTheme.dark.rawValue)
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                }

                Section(strings.language) {
                    Picker(strings.language, selection: $language) {
                        Text(strings.languageAuto).tag(AppLanguage.auto.rawValue)
                        Text("Русский").tag(AppLanguage.ru.rawValue)
                        Text("English").tag(AppLanguage.en.rawValue)
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                }

                Section(strings.whereConfigIs) {
                    Text(strings.configHint).font(.footnote).foregroundColor(.secondary)
                }

                Section(strings.about) {
                    Text("\(strings.version): \(appVersion)")
                    if let rcloneVersion {
                        Text("\(strings.builtOnRclone) \(rcloneVersion)")
                    }
                    Text("\(strings.projectPage): https://github.com/Chistovik92/opendisk")
                        .font(.footnote)
                    Text(strings.unsignedBuildNotice).font(.footnote).foregroundColor(.secondary)
                }
            }
            .navigationTitle(strings.settings)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(strings.close) { dismiss() }
                }
            }
        }
        .navigationViewStyle(.stack)
    }

    private var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "—"
    }
}
