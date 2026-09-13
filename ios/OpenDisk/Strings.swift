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
    var searchServices: String { t("Название, страна или другое имя", "Name, country or another name") }
    var loadingAllServices: String { t("Загружаю полный список сервисов rclone…", "Loading the full list of rclone services…") }
    var nothingFound: String { t("Ничего не найдено", "Nothing found") }
    var signInWithBrowser: String { t("Войти через браузер", "Sign in with a browser") }
    var browserWillOpen: String {
        t(
            "Откроется окно входа: выберите аккаунт и разрешите доступ. Пароль вводить и придумывать не нужно.",
            "A sign-in window will open: pick the account and allow access. No password to type or create."
        )
    }
    var waitingForBrowser: String { t("Подтвердите доступ в окне входа", "Confirm access in the sign-in window") }
    var preparingSignIn: String { t("Готовлю вход…", "Preparing sign-in…") }
    var openBrowserAgain: String { t("Открыть окно входа снова", "Open the sign-in window again") }

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
