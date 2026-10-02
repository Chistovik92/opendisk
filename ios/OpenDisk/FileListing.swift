import Foundation

// Как показать содержимое папки: тип файла, сортировка, фильтры, путь.
//
// Всё здесь — чистые функции на одном Foundation, без SwiftUI: так их
// проверяют обычные тесты (ios/OpenDiskTests), а экран только рисует
// результат. Это точное зеркало android-app/.../FileListing.kt, и тесты на
// обеих сторонах проверяют одни и те же случаи — см. docs/MOBILE-UI.md.

/// Что за файл — от этого зависят значок, цвет и то, чем его открывать.
enum FileKind: CaseIterable {
    case folder, image, video, audio, document, archive, apk, other

    var glyph: String {
        switch self {
        case .folder: return "📁"
        case .image: return "🖼"
        case .video: return "🎞"
        case .audio: return "🎵"
        case .document: return "📄"
        case .archive: return "🗜"
        case .apk: return "🤖"
        case .other: return "📎"
        }
    }

    private static let images: Set<String> = ["jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "svg", "tif", "tiff", "dng"]
    private static let videos: Set<String> = ["mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v", "ts", "flv", "wmv", "mpg", "mpeg"]
    private static let audios: Set<String> = ["mp3", "flac", "wav", "ogg", "m4a", "aac", "opus", "wma", "amr", "mid", "midi"]
    private static let documents: Set<String> = [
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "txt", "rtf", "md", "csv",
        "epub", "fb2", "djvu", "html", "htm", "xml", "json", "log",
    ]
    private static let archives: Set<String> = ["zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz", "iso"]

    static func of(_ entry: Entry) -> FileKind { entry.IsDir ? .folder : byName(entry.Name) }

    static func byName(_ name: String) -> FileKind {
        let ext = fileExtension(name)
        if images.contains(ext) { return .image }
        if videos.contains(ext) { return .video }
        if audios.contains(ext) { return .audio }
        if documents.contains(ext) { return .document }
        if archives.contains(ext) { return .archive }
        if ["apk", "apks", "xapk"].contains(ext) { return .apk }
        return .other
    }
}

/// Расширение в нижнем регистре без точки; у «.bashrc» и «README» его нет.
func fileExtension(_ name: String) -> String {
    guard let dot = name.lastIndex(of: "."), dot != name.startIndex else { return "" }
    let after = name.index(after: dot)
    guard after != name.endIndex else { return "" }
    return String(name[after...]).lowercased()
}

/// Порядок в папке. Папки всегда впереди — как в любом файловом менеджере.
enum SortOrder: String, CaseIterable {
    case name, size, date, type
}

/// Вид списка — три, как в ES File Explorer: короткий, подробный и плитки.
enum ViewMode: String, CaseIterable {
    case list, detailed, grid

    /// Следующий вид по кругу — кнопка в панели переключает именно так.
    func next() -> ViewMode {
        let all = ViewMode.allCases
        return all[(all.firstIndex(of: self)! + 1) % all.count]
    }
}

/// Как человек настроил папку: сортировка, скрытые, вид. Хранится в настройках.
struct ListingOptions: Equatable {
    var sort: SortOrder = .name
    var descending = false
    var showHidden = false
    var view: ViewMode = .detailed
}

/// То, что реально показывается: фильтр по скрытым и поиску, затем сортировка.
///
/// Поиск идёт по имени внутри открытой папки, без рекурсии: обход облака
/// целиком — это десятки тысяч запросов.
func arrange(_ entries: [Entry], _ options: ListingOptions, query: String = "") -> [Entry] {
    let needle = query.trimmingCharacters(in: .whitespaces).lowercased()
    let visible = entries.filter { entry in
        (options.showHidden || !entry.Name.hasPrefix(".")) &&
            (needle.isEmpty || entry.Name.lowercased().contains(needle))
    }

    /// −1, 0, 1: как сравнить два файла по выбранному признаку.
    func compare(_ a: Entry, _ b: Entry) -> Int {
        func order<T: Comparable>(_ x: T, _ y: T) -> Int { x < y ? -1 : (x > y ? 1 : 0) }
        var result: Int
        switch options.sort {
        case .name: result = 0
        case .size: result = order(a.Size, b.Size)
        case .date: result = order(a.ModTime ?? "", b.ModTime ?? "")
        case .type: result = order(fileExtension(a.Name), fileExtension(b.Name))
        }
        // Имя — второй ключ у всех порядков; путь — последний, чтобы порядок
        // не зависел от того, устойчива ли сортировка.
        if result == 0 { result = order(a.Name.lowercased(), b.Name.lowercased()) }
        if result == 0 { result = order(a.Path, b.Path) }
        return options.descending ? -result : result
    }

    // Папки впереди при любом порядке и любом направлении.
    return visible.sorted { a, b in
        if a.IsDir != b.IsDir { return a.IsDir }
        return compare(a, b) < 0
    }
}

/// Одна ступень пути: что показать и куда перейти по нажатию.
struct Crumb: Equatable {
    let label: String
    let path: String
}

/// Путь по ступеням: «Телефон › DCIM › Camera». Нажатие на ступень ведёт
/// в эту папку — в глубине облака иначе приходится нажимать «назад»
/// столько раз, сколько в пути папок.
func breadcrumbs(diskTitle: String, path: String) -> [Crumb] {
    var crumbs = [Crumb(label: diskTitle, path: "")]
    var current = ""
    for part in path.split(separator: "/") where !part.isEmpty {
        current = childPath(current, String(part))
        crumbs.append(Crumb(label: String(part), path: current))
    }
    return crumbs
}

/// Путь внутри диска: папка + имя, без ведущего слэша.
func childPath(_ parent: String, _ name: String) -> String {
    parent.isEmpty ? name : "\(parent)/\(name)"
}

/// Папка, в которой лежит путь; пусто — корень диска.
func parentPath(_ path: String) -> String {
    guard let slash = path.lastIndex(of: "/") else { return "" }
    return String(path[..<slash])
}

/// Пути от `anchor` до `target` включительно в порядке списка.
/// Нет опорной точки или её уже нет в списке — только сам `target`.
func rangeBetween(_ visible: [Entry], anchor: String?, target: String) -> Set<String> {
    guard let to = visible.firstIndex(where: { $0.Path == target }) else { return [] }
    guard let anchor, let from = visible.firstIndex(where: { $0.Path == anchor }) else { return [target] }
    return Set(visible[min(from, to)...max(from, to)].map(\.Path))
}

/// Имя для копии рядом с оригиналом: «отчёт.pdf» → «отчёт (2).pdf».
/// Расширение остаётся в конце — иначе файл перестал бы открываться.
func copyName(_ name: String, taken: Set<String>) -> String {
    let dot = name.lastIndex(of: ".").flatMap { $0 == name.startIndex ? nil : $0 } ?? name.endIndex
    let base = String(name[..<dot])
    let ext = String(name[dot...])
    var index = 2
    while taken.contains("\(base) (\(index))\(ext)") { index += 1 }
    return "\(base) (\(index))\(ext)"
}

/// Дата изменения для строки списка; пусто, если облако её не сказало.
///
/// rclone отдаёт время с наносекундами («…:00.123456789Z»), а системный
/// разбор ISO 8601 на такое не рассчитан, — дробную часть отбрасываем.
func formatModTime(_ modTime: String?, timeZone: TimeZone = .current, locale: Locale = .current) -> String {
    guard let modTime, !modTime.isEmpty else { return "" }
    let trimmed = modTime.replacingOccurrences(of: #"\.\d+"#, with: "", options: .regularExpression)
    let parser = ISO8601DateFormatter()
    parser.formatOptions = [.withInternetDateTime]
    guard let date = parser.date(from: trimmed) else { return "" }
    let formatter = DateFormatter()
    formatter.locale = locale
    formatter.timeZone = timeZone
    formatter.dateFormat = "d MMM yyyy, HH:mm"
    return formatter.string(from: date)
}

/// Русские числа склоняются по трём формам: «1 элемент», «3 элемента», «5 элементов».
func itemsLabel(_ count: Int, russian: Bool) -> String {
    guard russian else { return count == 1 ? "1 item" : "\(count) items" }
    let form: String
    if (11...14).contains(count % 100) {
        form = "элементов"
    } else if count % 10 == 1 {
        form = "элемент"
    } else if (2...4).contains(count % 10) {
        form = "элемента"
    } else {
        form = "элементов"
    }
    return "\(count) \(form)"
}
