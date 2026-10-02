import ImageIO
import SwiftUI
import UIKit

/// Файловый менеджер: путь ступенями, список или плитки, долгое нажатие
/// включает выбор, действия над выбранным — в нижней панели.
///
/// Экран устроен так же, как на Android (FileBrowser.kt) — docs/MOBILE-UI.md.
/// Верхняя панель своя, а не системная: иначе три её состояния — обычная,
/// выбор, поиск — пришлось бы собирать из кнопок системной панели и они
/// вышли бы не такими, как на Android.
///
/// Вглубь — не новым экраном навигации, а сменой папки на месте: тогда
/// нажатие на ступень пути ведёт сразу в нужную папку, а не вытаскивает
/// стопку экранов.
struct FileScreen: View {

    let strings: Strings
    @State var location: Location

    @EnvironmentObject private var files: FilesModel
    @Environment(\.presentationMode) private var presentation

    @State private var entries: [Entry] = []
    @State private var loading = true
    @State private var error: String?
    @State private var supportsLinks = false
    @State private var selected = Set<String>()
    @State private var anchor: String?
    @State private var query: String?
    @State private var renaming: Entry?
    @State private var deleting: [Entry]?
    @State private var propertiesOf: Entry?
    @State private var creating: Creating?
    @State private var link: LinkState?

    private enum Creating: Identifiable {
        case folder, file
        var id: Int { self == .folder ? 0 : 1 }
    }

    private struct LoadKey: Hashable {
        let location: Location
        let revision: Int
    }

    private var shown: [Entry] { arrange(entries, files.options, query: query ?? "") }
    private var selectedEntries: [Entry] { entries.filter { selected.contains($0.Path) } }
    private var selecting: Bool { !selected.isEmpty }
    private var cloudName: String? {
        if case .cloud(let name) = location.disk { return name }
        return nil
    }

    var body: some View {
        VStack(spacing: 0) {
            topBar
            crumbBar
            Divider()
            if let clip = files.clip { clipBar(clip) }
            content
            if let operation = files.operation { operationBar(operation) }
            if selecting { actionBar }
        }
        .overlay(alignment: .bottomTrailing) {
            if !selecting && query == nil && !loading && error == nil && !location.isVirtual {
                addButton.padding(16)
            }
        }
        .navigationBarHidden(true)
        .task(id: LoadKey(location: location, revision: files.revision)) { await load() }
        .alert(files.notice ?? "", isPresented: Binding(get: { files.notice != nil }, set: { if !$0 { files.notice = nil } })) {
            Button(strings.ok, role: .cancel) {}
        }
        .sheet(item: $creating) { kind in
            NameSheet(title: kind == .folder ? strings.newFolder : strings.newFile, initial: "", strings: strings) { name in
                Task {
                    if kind == .folder {
                        await files.createFolder(in: location, name: name, taken: Set(entries.map(\.Name)))
                    } else {
                        await files.createFile(in: location, name: name, taken: Set(entries.map(\.Name)))
                    }
                }
            }
        }
        .sheet(item: $renaming) { entry in
            NameSheet(title: strings.rename, initial: entry.Name, strings: strings) { name in
                Task {
                    let siblings = await siblingNames(of: entry)
                    await files.rename(entry, on: location.disk, to: name, siblings: siblings)
                    selected = []
                }
            }
        }
        .sheet(item: $propertiesOf) { entry in
            PropertiesSheet(entry: entry, location: location, strings: strings)
        }
        .sheet(item: $link) { state in
            LinkView(strings: strings, state: state)
        }
        .sheet(item: $files.shareItem) { item in
            ShareSheet(url: item.url)
        }
        .confirmationDialog(
            deleting.map { $0.count == 1 ? strings.deleteFileTitle($0[0].Name) : strings.deleteManyTitle($0.count) } ?? "",
            isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }),
            titleVisibility: .visible
        ) {
            Button(strings.delete, role: .destructive) {
                if let list = deleting {
                    deleting = nil
                    Task { await files.delete(list, on: location.disk) }
                }
            }
            Button(strings.cancel, role: .cancel) { deleting = nil }
        } message: {
            Text((deleting?.count ?? 0) == 1 ? strings.deleteFileExplanation : strings.deleteManyExplanation)
        }
    }

    // MARK: - Загрузка

    private func load() async {
        loading = true
        selected = []
        anchor = nil
        do {
            entries = try await files.load(location)
            error = nil
            if let name = cloudName, let info = try? await Rclone.shared.fsInfo(name) {
                supportsLinks = info.supportsPublicLink
            }
        } catch {
            self.error = error.localizedDescription
        }
        loading = false
    }

    /// Имена рядом с файлом. В категории список — не одна папка, поэтому
    /// соседей читаем с диска.
    private func siblingNames(of entry: Entry) async -> Set<String> {
        if !location.isVirtual { return Set(entries.map(\.Name)) }
        let list = try? await Rclone.shared.list(fs: Rclone.shared.fs(for: location.disk), path: parentPath(entry.Path))
        return Set((list ?? []).map(\.Name))
    }

    private func open(_ next: Location) {
        query = nil
        location = next
    }

    private func goBack() {
        if location.isVirtual || location.path.isEmpty {
            presentation.wrappedValue.dismiss()
        } else {
            open(Location(disk: location.disk, path: parentPath(location.path)))
        }
    }

    private var title: String {
        if let category = location.category { return categoryTitle(category, strings) }
        if location.path.isEmpty { return diskTitle(location.disk, strings) }
        return String(location.path.split(separator: "/").last ?? "")
    }

    // MARK: - Панели

    @ViewBuilder
    private var topBar: some View {
        HStack(spacing: 4) {
            if selecting {
                Button { selected = []; anchor = nil } label: { Text("✕").font(.title2).padding(8) }
                Text(strings.selectedCount(selected.count)).font(.headline)
                Spacer()
                let all = !shown.isEmpty && shown.allSatisfy { selected.contains($0.Path) }
                Button(all ? strings.clearSelection : strings.selectAll) {
                    selected = all ? [] : Set(shown.map(\.Path))
                }
            } else if query != nil {
                Button { query = nil } label: { Text("←").font(.title2).padding(8) }
                TextField(strings.searchHint, text: Binding(get: { query ?? "" }, set: { query = $0 }))
                    .textFieldStyle(.roundedBorder)
                    .autocapitalization(.none)
            } else {
                Button { goBack() } label: { Text("←").font(.title2).padding(8) }
                Text(title).font(.headline).lineLimit(1)
                Spacer()
                Button { query = "" } label: { Text("🔍").padding(8) }
                // Значок показывает, куда переключит нажатие: список → подробный → плитки.
                Button { files.options.view = files.options.view.next() } label: {
                    Text(viewGlyph(files.options.view.next())).font(.title2).padding(8)
                }
                menu
            }
        }
        .padding(.horizontal, 8)
        .frame(height: 48)
    }

    private func viewGlyph(_ mode: ViewMode) -> String {
        switch mode {
        case .list: return "≡"
        case .detailed: return "☰"
        case .grid: return "▦"
        }
    }

    private var menu: some View {
        Menu {
            Picker(strings.sortBy, selection: $files.options.sort) {
                Text(strings.sortName).tag(SortOrder.name)
                Text(strings.sortSize).tag(SortOrder.size)
                Text(strings.sortDate).tag(SortOrder.date)
                Text(strings.sortType).tag(SortOrder.type)
            }
            Toggle(strings.sortReverse, isOn: $files.options.descending)
            Toggle(strings.showHidden, isOn: $files.options.showHidden)
            if !location.isVirtual {
                Button(files.isBookmarked(location) ? strings.removeBookmark : strings.addBookmark) {
                    files.toggleBookmark(location)
                }
                Button(strings.newFolder) { creating = .folder }
                Button(strings.newFile) { creating = .file }
            }
            Button(strings.refresh) { Task { await load() } }
        } label: {
            Text("⋮").font(.title2).padding(8)
        }
    }

    /// Путь ступенями; нажатие на ступень открывает эту папку.
    private var crumbBar: some View {
        let crumbs: [Crumb] = location.category.map { [Crumb(label: categoryTitle($0, strings), path: "")] }
            ?? breadcrumbs(diskTitle: diskTitle(location.disk, strings), path: location.path)
        return ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 2) {
                    ForEach(Array(crumbs.enumerated()), id: \.offset) { index, crumb in
                        let last = index == crumbs.count - 1
                        Button { open(Location(disk: location.disk, path: crumb.path)) } label: {
                            Text(crumb.label)
                                .foregroundColor(last ? .primary : .accentColor)
                                .padding(.horizontal, 6)
                                .padding(.vertical, 6)
                        }
                        .disabled(last)
                        .id(index)
                        if !last { Text("›").foregroundColor(.secondary) }
                    }
                }
                .padding(.horizontal, 8)
            }
            // Глубокий путь не помещается — показываем его конец, где человек и находится.
            .onAppear { proxy.scrollTo(crumbs.count - 1, anchor: .trailing) }
            .onChange(of: crumbs.count) { count in proxy.scrollTo(count - 1, anchor: .trailing) }
        }
    }

    private func clipBar(_ clip: Clip) -> some View {
        HStack {
            Text(strings.inClipboard(clip.entries, move: clip.move)).lineLimit(1)
            Spacer()
            Button(strings.cancel) { files.cancelClip() }
            Button(strings.pasteHere) {
                Task { await files.paste(into: location, taken: Set(entries.map(\.Name))) }
            }
            .buttonStyle(.borderedProminent)
            .disabled(files.operation != nil || location.isVirtual)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(Color.secondary.opacity(0.12))
    }

    private func operationBar(_ text: String) -> some View {
        HStack(spacing: 12) {
            ProgressView()
            Text(text).lineLimit(2)
            Spacer()
        }
        .padding(14)
        .background(Color.secondary.opacity(0.15))
    }

    // MARK: - Содержимое

    @ViewBuilder
    private var content: some View {
        let list = shown
        if loading {
            Spacer()
            ProgressView(strings.readingFolder)
            Spacer()
        } else if let error {
            VStack(spacing: 12) {
                ScrollView { Text(error).foregroundColor(.red).padding() }
                // Доступ к облаку истёк — предлагаем вернуть его, а не оставляем с ошибкой.
                if let name = cloudName, AuthErrors.isExpired(error) {
                    Button(strings.signInAgain) { files.signInAgain(name) }
                        .buttonStyle(.borderedProminent)
                        .padding(.bottom, 16)
                }
            }
        } else if entries.isEmpty {
            Spacer()
            Text(strings.emptyFolder).foregroundColor(.secondary)
            Spacer()
        } else if list.isEmpty {
            Spacer()
            Text(strings.nothingFound).foregroundColor(.secondary)
            Spacer()
        } else if files.options.view == .grid {
            ScrollView {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 100), spacing: 4)], spacing: 4) {
                    ForEach(Array(list.enumerated()), id: \.offset) { _, entry in gridCell(entry, list) }
                }
                .padding(8)
            }
        } else {
            ScrollView {
                // Ключ с номером строки, а не один путь: Google Диск разрешает
                // два файла с одинаковым именем в одной папке.
                LazyVStack(spacing: 0) {
                    ForEach(Array(list.enumerated()), id: \.offset) { _, entry in listRow(entry, list) }
                }
            }
        }
    }

    private func listRow(_ entry: Entry, _ list: [Entry]) -> some View {
        let checked = selected.contains(entry.Path)
        let detailed = files.options.view == .detailed
        return HStack(spacing: 14) {
            KindIcon(entry: entry, disk: location.disk, size: detailed ? 44 : 36)
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.Name).lineLimit(1)
                if detailed {
                    let details = [
                        entry.IsDir ? folderCount(entry) : formatBytes(entry.Size),
                        formatModTime(entry.ModTime),
                    ].filter { !$0.isEmpty }.joined(separator: "  ·  ")
                    if !details.isEmpty {
                        Text(details).font(.footnote).foregroundColor(.secondary)
                    }
                }
            }
            Spacer()
            if selecting { Text(checked ? "☑" : "☐").font(.title3) }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(checked ? Color.accentColor.opacity(0.18) : Color.clear)
        .contentShape(Rectangle())
        .onTapGesture { activate(entry) }
        .onLongPressGesture { longPress(entry, list) }
    }

    /// Число элементов в папке на самом телефоне; у облака — пусто: для этого
    /// пришлось бы читать каждую папку отдельным запросом.
    private func folderCount(_ entry: Entry) -> String {
        guard location.disk == .local else { return "" }
        let url = Rclone.localRoot.appendingPathComponent(entry.Path)
        guard let count = try? FileManager.default.contentsOfDirectory(atPath: url.path).count else { return "" }
        return itemsLabel(count, russian: strings.russian)
    }

    private func gridCell(_ entry: Entry, _ list: [Entry]) -> some View {
        let checked = selected.contains(entry.Path)
        return VStack(spacing: 6) {
            KindIcon(entry: entry, disk: location.disk, size: 72)
            Text(entry.Name).font(.footnote).multilineTextAlignment(.center).lineLimit(2)
        }
        .padding(8)
        .frame(maxWidth: .infinity)
        .background(checked ? Color.accentColor.opacity(0.18) : Color.clear)
        .cornerRadius(10)
        .overlay(alignment: .topTrailing) { if checked { Text("✔").foregroundColor(.accentColor).padding(4) } }
        .contentShape(Rectangle())
        .onTapGesture { activate(entry) }
        .onLongPressGesture { longPress(entry, list) }
    }

    /// Нажатие: в режиме выбора — отметить, иначе открыть папку или файл.
    private func activate(_ entry: Entry) {
        if selecting {
            toggle(entry)
        } else if entry.IsDir {
            open(Location(disk: location.disk, path: entry.Path))
        } else {
            Task { await files.prepareShare(entry, from: location.disk) }
        }
    }

    private func toggle(_ entry: Entry) {
        if selected.contains(entry.Path) { selected.remove(entry.Path) } else { selected.insert(entry.Path) }
        anchor = entry.Path
    }

    /// Долгое нажатие: включает выбор, а в режиме выбора отмечает всё от
    /// прошлого отмеченного до этого файла — как в ES File Explorer.
    private func longPress(_ entry: Entry, _ list: [Entry]) {
        if selecting {
            selected.formUnion(rangeBetween(list, anchor: anchor, target: entry.Path))
            anchor = entry.Path
        } else {
            toggle(entry)
        }
    }

    // MARK: - Нижняя панель и кнопка «+»

    private var actionBar: some View {
        let picked = selectedEntries
        let single = picked.count == 1 ? picked[0] : nil
        return HStack {
            actionButton("⧉", strings.copy) { files.copy(picked, from: location.disk, move: false); selected = [] }
            actionButton("✂", strings.cutAction) { files.copy(picked, from: location.disk, move: true); selected = [] }
            actionButton("🗑", strings.delete) { deleting = picked }
            actionButton("⇪", strings.share) {
                if let single, !single.IsDir {
                    Task { await files.prepareShare(single, from: location.disk) }
                } else {
                    files.notice = strings.shareOneOnly
                }
            }
            Menu {
                if let single {
                    Button(strings.rename) { renaming = single }
                    Button(strings.properties) { propertiesOf = single }
                    if supportsLinks, !single.IsDir, let name = cloudName {
                        Button(strings.getLink) { askLink(cloud: name, entry: single) }
                    }
                }
                if location.disk == .local {
                    // Архивы — только на самом телефоне: через облако zip по частям не прочесть.
                    if let single, !single.IsDir, ZipArchive.isZip(single.Name) {
                        Button(strings.extractHere) { Task { await files.extract(single); selected = [] } }
                    }
                    Button(strings.compressToZip) { Task { await files.compress(picked); selected = [] } }
                } else {
                    Button(strings.downloadToPhone) { Task { await files.download(picked, from: location.disk) } }
                }
            } label: {
                VStack(spacing: 2) {
                    Text("⋮").font(.title3)
                    Text(strings.more).font(.caption2)
                }
                .frame(maxWidth: .infinity)
            }
        }
        .padding(.vertical, 6)
        .background(Color.secondary.opacity(0.12))
    }

    private func actionButton(_ glyph: String, _ label: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 2) {
                Text(glyph).font(.title3)
                Text(label).font(.caption2).lineLimit(1)
            }
            .frame(maxWidth: .infinity)
        }
    }

    private var addButton: some View {
        Menu {
            Button(strings.newFolder) { creating = .folder }
            Button(strings.newFile) { creating = .file }
        } label: {
            Text("＋")
                .font(.title)
                .foregroundColor(.white)
                .frame(width: 56, height: 56)
                .background(Color.accentColor)
                .clipShape(Circle())
                .shadow(radius: 3)
        }
    }

    private func askLink(cloud: String, entry: Entry) {
        link = LinkState(remotePath: entry.Path, url: nil, error: nil)
        Task {
            do {
                let url = try await Rclone.shared.publicLink(cloud, path: entry.Path)
                link = LinkState(remotePath: entry.Path, url: url, error: nil)
            } catch {
                link = LinkState(remotePath: entry.Path, url: nil, error: error.localizedDescription)
            }
        }
    }
}

// MARK: - Названия и значки

/// Как диск называется на экране.
func diskTitle(_ disk: Disk, _ strings: Strings) -> String {
    switch disk {
    case .local: return strings.phoneStorage
    case .cloud(let name): return name
    }
}

func categoryTitle(_ category: FileCategory, _ strings: Strings) -> String {
    switch category {
    case .images: return strings.catImages
    case .video: return strings.catVideo
    case .audio: return strings.catAudio
    case .documents: return strings.catDocuments
    case .downloads: return strings.catDownloads
    case .new: return strings.catNew
    }
}

/// Цвета типов одинаковы на обоих телефонах — см. docs/MOBILE-UI.md.
private func kindColor(_ kind: FileKind) -> Color {
    switch kind {
    case .folder: return Color(red: 0.95, green: 0.69, blue: 0.12)
    case .image: return Color(red: 0.26, green: 0.63, blue: 0.28)
    case .video: return Color(red: 0.90, green: 0.22, blue: 0.21)
    case .audio: return Color(red: 0.56, green: 0.14, blue: 0.67)
    case .document: return Color(red: 0.12, green: 0.53, blue: 0.90)
    case .archive: return Color(red: 0.43, green: 0.30, blue: 0.25)
    case .apk: return Color(red: 0.0, green: 0.54, blue: 0.48)
    case .other: return Color(red: 0.46, green: 0.46, blue: 0.46)
    }
}

/// Значок типа файла; у картинок на самом телефоне — миниатюра.
///
/// Миниатюры облачных файлов не делаем: для них пришлось бы скачать файл
/// целиком ради значка — на мобильной сети это дороже, чем пользы.
struct KindIcon: View {
    let entry: Entry
    let disk: Disk
    let size: CGFloat

    @State private var thumbnail: UIImage?

    var body: some View {
        let kind = FileKind.of(entry)
        ZStack {
            RoundedRectangle(cornerRadius: 10).fill(kindColor(kind).opacity(0.18))
            if let thumbnail {
                Image(uiImage: thumbnail).resizable().scaledToFill()
            } else {
                Text(kind.glyph).font(size > 56 ? .largeTitle : .title2)
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .task(id: entry.Path) {
            guard disk == .local, FileKind.of(entry) == .image else { return }
            thumbnail = await Thumbnails.load(Rclone.localRoot.appendingPathComponent(entry.Path), pixels: Int(size * 3))
        }
    }
}

/// Миниатюры с телефона: уменьшенная копия средствами ImageIO, а не полная
/// загрузка 12-мегапиксельного снимка ради квадрата в 44 точки.
enum Thumbnails {
    private static let cache = NSCache<NSString, UIImage>()

    static func load(_ url: URL, pixels: Int) async -> UIImage? {
        let key = url.path as NSString
        if let cached = cache.object(forKey: key) { return cached }
        let image = await Task.detached(priority: .utility) { () -> UIImage? in
            guard let source = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
            let options: [CFString: Any] = [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: pixels,
            ]
            return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary).map { UIImage(cgImage: $0) }
        }.value
        if let image { cache.setObject(image, forKey: key) }
        return image
    }
}

// MARK: - Листы

/// Имя новой папки или файла, новое имя файла.
struct NameSheet: View {
    let title: String
    let initial: String
    let strings: Strings
    let onDone: (String) -> Void

    @Environment(\.presentationMode) private var presentation
    @State private var text = ""

    var body: some View {
        NavigationView {
            Form {
                TextField(strings.name, text: $text)
                    .autocapitalization(.none)
                    .disableAutocorrection(true)
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button(strings.cancel) { presentation.wrappedValue.dismiss() }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(strings.ok) {
                        onDone(text)
                        presentation.wrappedValue.dismiss()
                    }
                    .disabled(text.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
        .navigationViewStyle(.stack)
        .onAppear { text = initial }
    }
}

struct PropertiesSheet: View {
    let entry: Entry
    let location: Location
    let strings: Strings

    @Environment(\.presentationMode) private var presentation

    var body: some View {
        NavigationView {
            Form {
                row(strings.propertyName, entry.Name)
                row(
                    strings.propertyLocation,
                    [diskTitle(location.disk, strings), parentPath(entry.Path)].filter { !$0.isEmpty }.joined(separator: " / ")
                )
                row(strings.propertyType, strings.kindName(FileKind.of(entry)))
                if !entry.IsDir { row(strings.propertySize, formatBytes(entry.Size)) }
                let modified = formatModTime(entry.ModTime)
                if !modified.isEmpty { row(strings.propertyModified, modified) }
            }
            .navigationTitle(strings.properties)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(strings.ok) { presentation.wrappedValue.dismiss() }
                }
            }
        }
        .navigationViewStyle(.stack)
    }

    private func row(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.footnote).foregroundColor(.secondary)
            Text(value)
        }
    }
}

/// Системное окно «Поделиться» — файл получает другое приложение.
struct ShareSheet: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [url], applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
