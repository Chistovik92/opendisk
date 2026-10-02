import Foundation
import SwiftUI

// Состояние и операции файлового менеджера. Зеркало OpenDiskModel.kt на
// Android: те же правила имён, тот же порядок действий — docs/MOBILE-UI.md.

/// Где мы: диск и папка, либо категория.
struct Location: Hashable {
    var disk: Disk
    var path: String = ""
    /// Не папка, а категория («Изображения»): файлы из разных папок одним
    /// списком. Пути у них настоящие, поэтому операции те же, а вставлять и
    /// создавать там нельзя — «текущей папки» нет.
    var category: FileCategory?

    var isVirtual: Bool { category != nil }
}

/// То, что лежит в буфере после «Копировать» или «Вырезать».
struct Clip {
    let disk: Disk
    let entries: [Entry]
    /// true — «Вырезать»: после вставки исходник удаляется.
    let move: Bool
}

/// Файл, который нужно отдать другому приложению.
struct ShareItem: Identifiable {
    let url: URL
    var id: String { url.path }
}

@MainActor
final class FilesModel: ObservableObject {

    @Published var options: ListingOptions { didSet { saveOptions() } }
    @Published var bookmarks: [Bookmark] { didSet { UserDefaults.standard.set(Bookmarks.encode(bookmarks), forKey: Keys.bookmarks) } }
    @Published var clip: Clip?
    /// Идёт долгая операция: копирование, удаление, скачивание. Текст — что именно.
    @Published var operation: String?
    /// Короткое сообщение: готово или что пошло не так.
    @Published var notice: String?
    @Published var shareItem: ShareItem?
    /// Растёт после каждой операции, меняющей файлы, — открытые папки перечитываются.
    @Published private(set) var revision = 0

    var strings: Strings
    /// Повторный вход в облако. Выставляет главный экран: окно входа Apple
    /// открывает он, а экран папки только просит об этом.
    var signInAgain: (String) -> Void = { _ in }

    init(strings: Strings) {
        self.strings = strings
        let defaults = UserDefaults.standard
        options = ListingOptions(
            sort: SortOrder(rawValue: defaults.string(forKey: Keys.sort) ?? "") ?? .name,
            descending: defaults.bool(forKey: Keys.sortDescending),
            showHidden: defaults.bool(forKey: Keys.showHidden),
            view: ViewMode(rawValue: defaults.string(forKey: Keys.view) ?? "") ?? .detailed
        )
        bookmarks = Bookmarks.decode(defaults.string(forKey: Keys.bookmarks))
    }

    private enum Keys {
        static let sort = "listing.sort"
        static let sortDescending = "listing.sortDescending"
        static let showHidden = "listing.showHidden"
        static let view = "listing.view"
        static let bookmarks = "bookmarks"
    }

    private func saveOptions() {
        let defaults = UserDefaults.standard
        defaults.set(options.sort.rawValue, forKey: Keys.sort)
        defaults.set(options.descending, forKey: Keys.sortDescending)
        defaults.set(options.showHidden, forKey: Keys.showHidden)
        defaults.set(options.view.rawValue, forKey: Keys.view)
    }

    // MARK: - Чтение папки

    /// Содержимое папки или категории, как его видит экран (до сортировки).
    func load(_ location: Location) async throws -> [Entry] {
        let rclone = Rclone.shared
        if let category = location.category {
            let all = try await rclone.list(fs: rclone.fs(for: .local), path: "", recursive: true)
            return category.select(from: all, now: Date().timeIntervalSince1970)
        }
        let entries = try await rclone.list(fs: rclone.fs(for: location.disk), path: location.path)
        // rclone.conf лежит в той же папке, что и файлы человека. Удалить его
        // здесь значило бы потерять все облака разом, — не показываем.
        if location.disk == .local && location.path.isEmpty {
            return entries.filter { $0.Path != "rclone.conf" }
        }
        return entries
    }

    // MARK: - Закладки

    func isBookmarked(_ location: Location) -> Bool {
        !location.isVirtual && bookmarks.contains { $0.disk == location.disk && $0.path == location.path }
    }

    func toggleBookmark(_ location: Location) {
        guard !location.isVirtual else { return }
        if let index = bookmarks.firstIndex(where: { $0.disk == location.disk && $0.path == location.path }) {
            bookmarks.remove(at: index)
        } else {
            bookmarks.append(Bookmark(disk: location.disk, path: location.path))
        }
    }

    func removeBookmark(_ bookmark: Bookmark) {
        bookmarks.removeAll { $0.key == bookmark.key }
    }

    // MARK: - Операции

    /// Долгая операция с индикатором. Ошибка не роняет экран, а приходит
    /// сообщением: файловый менеджер, который закрывается от «нет места»,
    /// хуже, чем тот, который об этом сообщает.
    private func run(_ label: String, refresh: Bool = true, _ block: () async throws -> Void) async {
        guard operation == nil else { return }
        operation = label
        do {
            try await block()
        } catch {
            notice = error.localizedDescription
        }
        operation = nil
        if refresh { revision += 1 }
    }

    func createFolder(in location: Location, name: String, taken: Set<String>) async {
        let clean = name.trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard !clean.isEmpty, !location.isVirtual else { return }
        if taken.contains(clean) { notice = strings.nameTaken(clean); return }
        await run(strings.creatingFolder) {
            try await Rclone.shared.mkdir(fs: Rclone.shared.fs(for: location.disk), path: childPath(location.path, clean))
        }
    }

    /// У rclone нет «создать файл», поэтому пустой файл кладётся во временную
    /// папку и уходит на диск обычной копией — так работает и в облаке, и на телефоне.
    func createFile(in location: Location, name: String, taken: Set<String>) async {
        let clean = name.trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard !clean.isEmpty, !location.isVirtual else { return }
        if taken.contains(clean) { notice = strings.nameTaken(clean); return }
        await run(strings.creatingFile) {
            try await withStage { stage in
                FileManager.default.createFile(atPath: stage.appendingPathComponent(clean).path, contents: Data())
                try await Rclone.shared.copyFile(
                    from: stage.path, clean,
                    to: Rclone.shared.fs(for: location.disk), childPath(location.path, clean)
                )
            }
        }
    }

    func rename(_ entry: Entry, on disk: Disk, to newName: String, siblings: Set<String>) async {
        let clean = newName.trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard !clean.isEmpty, clean != entry.Name else { return }
        // Занятое имя — отказ, а не молчаливая замена соседа.
        if siblings.contains(clean) { notice = strings.nameTaken(clean); return }
        await run(strings.renaming) {
            try await transfer(entry, from: disk, to: disk, targetPath: childPath(parentPath(entry.Path), clean), move: true)
        }
    }

    func delete(_ entries: [Entry], on disk: Disk) async {
        guard !entries.isEmpty else { return }
        let label = entries.count == 1 ? strings.deleting(entries[0].Name) : strings.deletingMany(entries.count)
        await run(label) {
            let fs = Rclone.shared.fs(for: disk)
            for entry in entries {
                if entry.IsDir {
                    try await Rclone.shared.purge(fs: fs, path: entry.Path)
                } else {
                    try await Rclone.shared.deleteFile(fs: fs, path: entry.Path)
                }
            }
        }
    }

    func copy(_ entries: [Entry], from disk: Disk, move: Bool) {
        guard !entries.isEmpty else { return }
        clip = Clip(disk: disk, entries: entries, move: move)
    }

    /// Вставка в папку. Имя занято — другое имя («отчёт (2).pdf»), а не
    /// перезапись: rclone иначе молча затёр бы одноимённый файл.
    func paste(into location: Location, taken: Set<String>) async {
        guard let clip, !location.isVirtual else { return }
        var names = taken
        var jobs: [(Entry, String)] = []
        for entry in clip.entries {
            var target = childPath(location.path, entry.Name)
            // Вырезать и вставить туда же — делать нечего.
            if clip.move && clip.disk == location.disk && target == entry.Path { continue }
            if names.contains(entry.Name) { target = childPath(location.path, copyName(entry.Name, taken: names)) }
            // Папку в саму себя не положить: rclone ушёл бы в бесконечное копирование.
            if clip.disk == location.disk && entry.IsDir && (location.path + "/").hasPrefix(entry.Path + "/") {
                notice = strings.cannotPasteIntoItself
                return
            }
            names.insert(String(target.split(separator: "/").last ?? ""))
            jobs.append((entry, target))
        }
        if jobs.isEmpty { self.clip = nil; return }
        let label: String
        if jobs.count > 1 {
            label = clip.move ? strings.movingMany(jobs.count) : strings.copyingMany(jobs.count)
        } else {
            label = clip.move ? strings.moving(jobs[0].0.Name) : strings.copying(jobs[0].0.Name)
        }
        await run(label) {
            for (entry, target) in jobs {
                try await transfer(entry, from: clip.disk, to: location.disk, targetPath: target, move: clip.move)
            }
            self.clip = nil
        }
    }

    func cancelClip() { clip = nil }

    /// Скачать на телефон — в «Download» в папке OpenDisk, где его видно в «Файлах».
    func download(_ entries: [Entry], from disk: Disk) async {
        guard !entries.isEmpty, case .cloud = disk else { return }
        let local = Rclone.shared.fs(for: .local)
        let label = entries.count == 1 ? strings.downloading(entries[0].Name) : strings.downloadingMany(entries.count)
        await run(label, refresh: false) {
            try await Rclone.shared.mkdir(fs: local, path: FilesModel.downloadDir)
            // Уже скачанное раньше не перезаписываем: «фото.jpg» → «фото (2).jpg».
            var taken = Set(try await Rclone.shared.list(fs: local, path: FilesModel.downloadDir).map(\.Name))
            var lastName = ""
            for entry in entries {
                let name = taken.contains(entry.Name) ? copyName(entry.Name, taken: taken) : entry.Name
                taken.insert(name)
                lastName = name
                try await transfer(entry, from: disk, to: .local, targetPath: childPath(FilesModel.downloadDir, name), move: false)
            }
            notice = strings.downloaded(entries.count == 1 ? "\(FilesModel.downloadDir)/\(lastName)" : FilesModel.downloadDir)
        }
    }

    static let downloadDir = "Download"

    /// Файл для «Отправить»: облачный сначала копируется во временную папку —
    /// другое приложение получает от системы обычный файл, а не адрес в облаке.
    func prepareShare(_ entry: Entry, from disk: Disk) async {
        await run(strings.preparingFile(entry.Name), refresh: false) {
            if disk == .local {
                shareItem = ShareItem(url: Rclone.localRoot.appendingPathComponent(entry.Path))
                return
            }
            let dir = FileManager.default.temporaryDirectory.appendingPathComponent("share", isDirectory: true)
            try? FileManager.default.removeItem(at: dir)
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            try await Rclone.shared.copyFile(from: Rclone.shared.fs(for: disk), entry.Path, to: dir.path, entry.Name)
            shareItem = ShareItem(url: dir.appendingPathComponent(entry.Name))
        }
    }

    // MARK: - Архивы (только папка на телефоне)

    func extract(_ entry: Entry) async {
        let zip = Rclone.localRoot.appendingPathComponent(entry.Path)
        await run(strings.extracting(entry.Name)) {
            let parent = zip.deletingLastPathComponent()
            let taken = Set((try? FileManager.default.contentsOfDirectory(atPath: parent.path)) ?? [])
            let base = ZipArchive.folderName(for: entry.Name)
            let folder = taken.contains(base) ? copyName(base, taken: taken) : base
            _ = try await Task.detached { try ZipArchive.extract(zip, into: parent.appendingPathComponent(folder)) }.value
        }
    }

    func compress(_ entries: [Entry]) async {
        guard !entries.isEmpty else { return }
        let sources = entries.map { Rclone.localRoot.appendingPathComponent($0.Path) }
        await run(strings.compressing(entries.count)) {
            let parent = sources[0].deletingLastPathComponent()
            let taken = Set((try? FileManager.default.contentsOfDirectory(atPath: parent.path)) ?? [])
            let base = entries.count == 1 ? ZipArchive.folderName(for: entries[0].Name) : strings.archiveName
            let name = taken.contains("\(base).zip") ? copyName("\(base).zip", taken: taken) : "\(base).zip"
            try await Task.detached { try ZipArchive.create(from: sources, to: parent.appendingPathComponent(name)) }.value
        }
    }

    // MARK: - Перенос между дисками

    /// Копирует или переносит файл либо папку с диска на диск.
    private func transfer(_ entry: Entry, from: Disk, to: Disk, targetPath: String, move: Bool) async throws {
        let rclone = Rclone.shared
        let src = rclone.fs(for: from)
        let dst = rclone.fs(for: to)
        switch (entry.IsDir, move) {
        case (true, true): try await rclone.moveDir(from: src, entry.Path, to: dst, targetPath)
        case (true, false): try await rclone.copyDir(from: src, entry.Path, to: dst, targetPath)
        case (false, true): try await rclone.moveFile(from: src, entry.Path, to: dst, targetPath)
        case (false, false): try await rclone.copyFile(from: src, entry.Path, to: dst, targetPath)
        }
    }

    private func withStage(_ block: (URL) async throws -> Void) async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("stage-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        try await block(dir)
    }

    // MARK: - Место

    /// Занято и всего на памяти телефона; nil — система не ответила.
    static func localSpace() -> (used: Int64, total: Int64)? {
        let keys: Set<URLResourceKey> = [.volumeTotalCapacityKey, .volumeAvailableCapacityForImportantUsageKey]
        guard let values = try? Rclone.localRoot.resourceValues(forKeys: keys),
              let total = values.volumeTotalCapacity,
              let free = values.volumeAvailableCapacityForImportantUsage
        else { return nil }
        return (Int64(total) - free, Int64(total))
    }
}
