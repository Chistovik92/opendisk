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

    // MARK: - Файловый менеджер 0.6.0
    //
    // Названия те же, что в android-app/.../MobileStrings.kt: интерфейс на
    // телефонах один, см. docs/MOBILE-UI.md.

    var sectionDevice: String { t("На этом телефоне", "On this phone") }
    var phoneStorage: String { t("Папка OpenDisk", "OpenDisk folder") }
    var sectionCategories: String { t("Категории", "Categories") }
    var sectionBookmarks: String { t("Закладки", "Bookmarks") }
    var catImages: String { t("Изображения", "Images") }
    var catVideo: String { t("Видео", "Video") }
    var catAudio: String { t("Звук", "Audio") }
    var catDocuments: String { t("Документы", "Documents") }
    var catDownloads: String { t("Загрузки", "Downloads") }
    var catNew: String { t("Новые", "New") }
    var addBookmark: String { t("В закладки", "Add bookmark") }
    var removeBookmark: String { t("Убрать из закладок", "Remove bookmark") }
    var noBookmarks: String {
        t("Папку можно добавить в закладки через меню «⋮» в ней.", "Add a folder to bookmarks from the ⋮ menu inside it.")
    }
    func used(_ used: String, _ total: String) -> String { t("Занято \(used) из \(total)", "\(used) of \(total) used") }

    var open: String { t("Открыть", "Open") }
    var share: String { t("Отправить", "Share") }
    var downloadToPhone: String { t("Скачать в телефон", "Download to phone") }
    var cutAction: String { t("Вырезать", "Cut") }
    var rename: String { t("Переименовать", "Rename") }
    var getLink: String { t("Ссылка на скачивание", "Download link") }
    var newFolder: String { t("Новая папка", "New folder") }
    var newFile: String { t("Новый файл", "New file") }
    var pasteHere: String { t("Вставить сюда", "Paste here") }
    var more: String { t("Ещё", "More") }
    var ok: String { t("Готово", "OK") }
    var refresh: String { t("Обновить", "Refresh") }
    var save: String { t("Сохранить", "Save") }

    func inClipboard(_ entries: [Entry], move: Bool) -> String {
        let what = entries.count == 1 ? entries[0].Name : itemsLabel(entries.count, russian: russian)
        return move ? t("Вырезано: \(what)", "Cut: \(what)") : t("Скопировано: \(what)", "Copied: \(what)")
    }

    var creatingFolder: String { t("Создаю папку…", "Creating the folder…") }
    var creatingFile: String { t("Создаю файл…", "Creating the file…") }
    var renaming: String { t("Переименовываю…", "Renaming…") }
    func deleting(_ name: String) -> String { t("Удаляю «\(name)»…", "Deleting «\(name)»…") }
    func copying(_ name: String) -> String { t("Копирую «\(name)»…", "Copying «\(name)»…") }
    func moving(_ name: String) -> String { t("Переношу «\(name)»…", "Moving «\(name)»…") }
    func downloading(_ name: String) -> String { t("Скачиваю «\(name)»…", "Downloading «\(name)»…") }
    func deletingMany(_ count: Int) -> String { t("Удаляю: \(itemsLabel(count, russian: true))…", "Deleting \(itemsLabel(count, russian: false))…") }
    func copyingMany(_ count: Int) -> String { t("Копирую: \(itemsLabel(count, russian: true))…", "Copying \(itemsLabel(count, russian: false))…") }
    func movingMany(_ count: Int) -> String { t("Переношу: \(itemsLabel(count, russian: true))…", "Moving \(itemsLabel(count, russian: false))…") }
    func downloadingMany(_ count: Int) -> String { t("Скачиваю: \(itemsLabel(count, russian: true))…", "Downloading \(itemsLabel(count, russian: false))…") }
    func downloaded(_ path: String) -> String { t("Скачано: \(path)", "Downloaded: \(path)") }
    func preparingFile(_ name: String) -> String { t("Готовлю «\(name)»…", "Preparing «\(name)»…") }
    func extracting(_ name: String) -> String { t("Распаковываю «\(name)»…", "Extracting «\(name)»…") }
    func compressing(_ count: Int) -> String { t("Сжимаю: \(itemsLabel(count, russian: true))…", "Compressing \(itemsLabel(count, russian: false))…") }
    var archiveName: String { t("Архив", "Archive") }
    var cannotPasteIntoItself: String { t("Папку нельзя вставить в саму себя.", "A folder cannot be pasted into itself.") }
    func nameTaken(_ name: String) -> String {
        t("Имя «\(name)» уже занято в этой папке.", "The name «\(name)» is already taken in this folder.")
    }

    func selectedCount(_ count: Int) -> String { t("Выбрано: \(count)", "Selected: \(count)") }
    var selectAll: String { t("Выбрать всё", "Select all") }
    var clearSelection: String { t("Снять выбор", "Clear selection") }
    var searchHint: String { t("Имя файла или его часть", "File name or part of it") }
    var sortBy: String { t("Сортировка", "Sort by") }
    var sortName: String { t("По имени", "Name") }
    var sortSize: String { t("По размеру", "Size") }
    var sortDate: String { t("По дате", "Date") }
    var sortType: String { t("По типу", "Type") }
    var sortReverse: String { t("В обратном порядке", "Reverse order") }
    var showHidden: String { t("Скрытые файлы", "Hidden files") }

    var properties: String { t("Свойства", "Properties") }
    var propertyName: String { t("Имя", "Name") }
    var propertyLocation: String { t("Где лежит", "Location") }
    var propertyType: String { t("Тип", "Type") }
    var propertySize: String { t("Размер", "Size") }
    var propertyModified: String { t("Изменён", "Modified") }
    func kindName(_ kind: FileKind) -> String {
        switch kind {
        case .folder: return t("Папка", "Folder")
        case .image: return t("Изображение", "Image")
        case .video: return t("Видео", "Video")
        case .audio: return t("Звук", "Audio")
        case .document: return t("Документ", "Document")
        case .archive: return t("Архив", "Archive")
        case .apk: return t("Приложение Android", "Android app")
        case .other: return t("Файл", "File")
        }
    }

    func deleteFileTitle(_ name: String) -> String { t("Удалить «\(name)»?", "Delete «\(name)»?") }
    var deleteFileExplanation: String {
        t("Файл будет удалён без возможности вернуть.", "The file will be deleted and cannot be restored.")
    }
    func deleteManyTitle(_ count: Int) -> String {
        t("Удалить: \(itemsLabel(count, russian: true))?", "Delete \(itemsLabel(count, russian: false))?")
    }
    var deleteManyExplanation: String {
        t("Отмеченное будет удалено без возможности вернуть.", "The selected items will be deleted and cannot be restored.")
    }
    var shareOneOnly: String { t("Отправить можно только один файл за раз.", "Only one file can be shared at a time.") }
    var extractHere: String { t("Распаковать сюда", "Extract here") }
    var compressToZip: String { t("Сжать в zip", "Compress to zip") }
}
