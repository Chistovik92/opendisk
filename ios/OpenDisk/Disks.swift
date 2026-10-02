import Foundation

// Диски, закладки и категории — чистая логика без SwiftUI.
// Зеркало android-app/.../Disks.kt и Categories.kt, см. docs/MOBILE-UI.md.

/// Диск во встроенном файловом менеджере.
///
/// На iPhone приложение видит только свою песочницу: папка OpenDisk в
/// «Файлах» (это «Документы» приложения) и облака. Память телефона целиком,
/// как на Android, системой не отдаётся никому.
enum Disk: Hashable {
    /// Папка OpenDisk на этом iPhone.
    case local
    /// Облако из `rclone.conf`.
    case cloud(String)

    /// Устойчивый ключ для списков.
    var key: String {
        switch self {
        case .local: return "local"
        case .cloud(let name): return "cloud:\(name)"
        }
    }
}

/// Папка, к которой хочется возвращаться одним нажатием.
struct Bookmark: Equatable, Identifiable {
    let disk: Disk
    let path: String

    var key: String { disk.key + "\u{1}" + path }
    var id: String { key }
}

/// Закладки хранятся одной строкой в настройках; порядок — как добавил человек.
enum Bookmarks {

    private static let field = "\u{1}"
    private static let record = "\u{2}"

    static func encode(_ bookmarks: [Bookmark]) -> String {
        bookmarks.map { bookmark -> String in
            switch bookmark.disk {
            case .local: return ["local", bookmark.path].joined(separator: field)
            case .cloud(let name): return ["cloud", name, bookmark.path].joined(separator: field)
            }
        }.joined(separator: record)
    }

    static func decode(_ text: String?) -> [Bookmark] {
        guard let text, !text.isEmpty else { return [] }
        var seen = Set<String>()
        return text.components(separatedBy: record).compactMap { item -> Bookmark? in
            let parts = item.components(separatedBy: field)
            let bookmark: Bookmark?
            switch parts.first {
            case "local" where parts.count >= 2: bookmark = Bookmark(disk: .local, path: parts[1])
            case "cloud" where parts.count >= 3: bookmark = Bookmark(disk: .cloud(parts[1]), path: parts[2])
            default: bookmark = nil
            }
            // Одна и та же папка дважды не нужна.
            guard let bookmark, seen.insert(bookmark.key).inserted else { return nil }
            return bookmark
        }
    }
}

/// Категории главного экрана — как «Изображения», «Видео» и «Загрузки» в
/// ES File Explorer: файлы одного рода из всех папок одним списком.
enum FileCategory: CaseIterable {
    case images, video, audio, documents, downloads, new

    /// Сколько дней файл считается «новым».
    static let newDays = 7

    /// Предел списка: десятки тысяч строк в одном списке — это и память, и
    /// бесполезная прокрутка.
    static let limit = 5_000

    /// Расширения документов — тот же список, что у категории на Android.
    static let documentExtensions: Set<String> = [
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "txt", "rtf", "csv", "epub", "fb2", "djvu",
    ]

    /// Подходит ли файл категории. `now` — секунды с 1970 года, для «Новых».
    func matches(_ entry: Entry, now: TimeInterval) -> Bool {
        if entry.IsDir { return false }
        let parts = entry.Path.split(separator: "/")
        // Скрытые файлы и каталоги кэша не нужны ни в «Новых», ни в «Изображениях».
        if parts.contains(where: { $0.hasPrefix(".") }) { return false }
        switch self {
        case .images: return FileKind.byName(entry.Name) == .image
        case .video: return FileKind.byName(entry.Name) == .video
        case .audio: return FileKind.byName(entry.Name) == .audio
        case .documents: return FileCategory.documentExtensions.contains(fileExtension(entry.Name))
        case .downloads: return entry.Path.hasPrefix("Download/")
        case .new:
            // Размер больше нуля, как у запроса на Android: пустые записи — не файлы.
            guard entry.Size > 0, let modified = FileCategory.epoch(entry.ModTime) else { return false }
            return modified > now - Double(FileCategory.newDays) * 86_400
        }
    }

    /// Категория из списка файлов: подходящие, новые первыми, не больше предела.
    func select(from entries: [Entry], now: TimeInterval) -> [Entry] {
        entries
            .filter { matches($0, now: now) }
            .sorted { (FileCategory.epoch($0.ModTime) ?? 0) > (FileCategory.epoch($1.ModTime) ?? 0) }
            .prefix(FileCategory.limit)
            .map { $0 }
    }

    static func epoch(_ modTime: String?) -> TimeInterval? {
        guard let modTime, !modTime.isEmpty else { return nil }
        let trimmed = modTime.replacingOccurrences(of: #"\.\d+"#, with: "", options: .regularExpression)
        let parser = ISO8601DateFormatter()
        parser.formatOptions = [.withInternetDateTime]
        return parser.date(from: trimmed)?.timeIntervalSince1970
    }
}

/// Признаки того, что доступ к облаку истёк. Те же, что в
/// rclone-bridge/.../AuthErrors.kt: rclone сам подсказывает «config reconnect»,
/// а человеку с телефоном такую команду не выполнить.
enum AuthErrors {

    private static let markers = [
        "invalid_grant",
        "couldn't fetch token",
        "token expired",
        "config reconnect",
        "empty token found",
    ]

    static func isExpired(_ message: String) -> Bool {
        let lowered = message.lowercased()
        return markers.contains { lowered.contains($0) }
    }
}

/// Новый список «нужно войти заново» после фоновой проверки токенов.
///
/// Проверенные и отозвавшиеся из списка убираются, протухшие добавляются.
/// Облака, до которых проверка не дошла (нет сети, долго не отвечали),
/// остаются как были — по неудачной проверке о токене не судим. Удалённые
/// облака пропадают вовсе. Зеркало `TokenRefreshWorker.merge` на Android.
func mergeNeedsSignIn(before: Set<String>, existing: Set<String>, expired: Set<String>, unreachable: Set<String> = []) -> Set<String> {
    Set(before.filter { existing.contains($0) && unreachable.contains($0) }).union(expired)
}
