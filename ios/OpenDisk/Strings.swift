import Foundation

/// Язык интерфейса.
enum AppLanguage: String, CaseIterable, Identifiable {
    case auto, ru, en
    var id: String { rawValue }
}

/// Оформление.
enum AppTheme: String, CaseIterable, Identifiable {
    case auto, light, dark
    var id: String { rawValue }
}

/// Строки интерфейса.
///
/// Устроено так же, как на десктопе и на Android: оба языка стоят рядом, и
/// пропущенный перевод не скомпилируется. Файлы `.strings` были бы привычнее
/// для iOS, но тогда переводы жили бы в трёх местах и расходились ровно так,
/// как это обычно и происходит.
struct Strings {

    let russian: Bool

    private func t(_ ru: String, _ en: String) -> String { russian ? ru : en }

    static func of(_ language: AppLanguage) -> Strings {
        switch language {
        case .ru: return Strings(russian: true)
        case .en: return Strings(russian: false)
        case .auto: return Strings(russian: Locale.preferredLanguages.first?.hasPrefix("ru") ?? false)
        }
    }

    var clouds: String { t("Облака", "Clouds") }
    var starting: String { t("Запускаю rclone…", "Starting rclone…") }
    var noClouds: String {
        t(
            "Облаков пока нет. Добавьте первое кнопкой «+» — или положите свой rclone.conf в папку OpenDisk в «Файлах».",
            "No clouds yet. Add the first one with «+» — or drop your rclone.conf into the OpenDisk folder in Files."
        )
    }
    var add: String { t("Добавить", "Add") }
    var adding: String { t("Добавляю…", "Adding…") }
    var cancel: String { t("Отмена", "Cancel") }
    var back: String { t("Назад", "Back") }
    var close: String { t("Закрыть", "Close") }
    var delete: String { t("Удалить", "Delete") }
    var name: String { t("Название", "Name") }
    var login: String { t("Логин", "Login") }
    var password: String { t("Пароль", "Password") }
    var appPassword: String { t("Пароль приложения", "App password") }
    var serverUrl: String { t("Адрес сервера", "Server address") }
    var host: String { t("Хост", "Host") }
    var emptyFolder: String { t("Папка пуста", "The folder is empty") }
    var readingFolder: String { t("Читаю папку…", "Reading the folder…") }

    var linkTitle: String { t("Ссылка на скачивание", "Download link") }
    var linkAsking: String { t("Спрашиваю ссылку у облака…", "Asking the cloud for a link…") }
    var linkExplanation: String {
        t(
            "Ссылку выдал сам сервис. Файл по ней скачает любой, у кого она есть, без входа в аккаунт.",
            "The link comes from the service itself. Anyone who has it can download the file without signing in."
        )
    }
    var copy: String { t("Копировать", "Copy") }
    var copied: String { t("Скопировано", "Copied") }

    func deleteTitle(_ cloud: String) -> String { t("Удалить «\(cloud)»?", "Delete «\(cloud)»?") }
    var deleteExplanation: String {
        t(
            "Из приложения пропадёт только подключение. Файлы в самом облаке останутся нетронутыми.",
            "Only the connection is removed from the app. The files in the cloud itself stay untouched."
        )
    }

    var chooseService: String { t("Какое облако добавить", "Which cloud to add") }
    var yandexDisk: String { t("Яндекс.Диск", "Yandex.Disk") }
    var mailru: String { t("Облако Mail.ru", "Mail.ru Cloud") }
    var anyWebdav: String { t("Любой сервер WebDAV", "Any WebDAV server") }
    var sshAccess: String { t("Доступ по SSH", "Access over SSH") }
    var ftpServer: String { t("Сервер FTP", "FTP server") }
    var yandexHint: String {
        t(
            "Яндексу нужен пароль приложения, а не пароль от аккаунта: создаётся на id.yandex.ru в разделе «Пароли приложений».",
            "Yandex needs an app password rather than your account password: create one at id.yandex.ru under «App passwords»."
        )
    }
    var mailruHint: String {
        t(
            "Mail.ru не принимает основной пароль от аккаунта — нужен отдельный пароль для внешних приложений.",
            "Mail.ru does not accept your main account password — a separate password for external applications is required."
        )
    }
    var browserServicesMissing: String {
        t(
            "Google Диск, Dropbox и OneDrive подтверждают доступ в браузере — на телефоне этого пока нет. Добавьте их в OpenDisk на компьютере и перенесите файл rclone.conf.",
            "Google Drive, Dropbox and OneDrive confirm access in a browser — that is not available on the phone yet. Add them in OpenDisk on a computer and bring the rclone.conf file over."
        )
    }

    var settings: String { t("Настройки", "Settings") }
    var theme: String { t("Оформление", "Appearance") }
    var themeAuto: String { t("Как в системе", "Same as system") }
    var themeLight: String { t("Светлое", "Light") }
    var themeDark: String { t("Тёмное", "Dark") }
    var language: String { t("Язык интерфейса", "Interface language") }
    var languageAuto: String { t("Как в системе", "Same as system") }
    var about: String { t("О приложении", "About") }
    var version: String { t("Версия", "Version") }
    var builtOnRclone: String { t("Работает на rclone", "Powered by rclone") }
    var projectPage: String { t("Страница проекта", "Project page") }
    var whereConfigIs: String { t("Где лежит список облаков", "Where the list of clouds lives") }
    var configHint: String {
        t(
            "Папка OpenDisk в приложении «Файлы». Туда же можно положить rclone.conf с компьютера — со всеми облаками, включая те, что подтверждают доступ в браузере.",
            "The OpenDisk folder in the Files app. You can put an rclone.conf from a computer there too — with every cloud, including the ones that confirm access in a browser."
        )
    }

    /// Честно про то, чего на iPhone нет и почему.
    var unsignedBuildNotice: String {
        t(
            "Эта сборка не подписана сертификатом Apple: подключить облако диском, как на компьютере, iOS не даёт никакому приложению — для этого нужно расширение, а его обязана подписывать Apple. Файлы открываются и раздаются ссылками прямо здесь.",
            "This build is not signed with an Apple certificate: iOS lets no application mount a cloud as a drive — that needs an extension, and Apple has to sign it. Files open and are shared by link right here."
        )
    }
}
