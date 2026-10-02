import SwiftUI

/// Главный экран: хранилище, категории, закладки и облака.
///
/// Устроен так же, как на Android (HomeScreen.kt) — docs/MOBILE-UI.md.
/// Свои строки на прокрутке, а не `List`: плитки категорий в строке списка
/// ловили бы нажатие всей строкой, и у каждого облака рядом с переходом
/// нужно меню «⋮», как на Android.
struct CloudListView: View {

    let strings: Strings

    @StateObject private var model = CloudsModel()
    @EnvironmentObject private var files: FilesModel
    @State private var adding = false
    @State private var settings = false
    @State private var cloudToDelete: String?

    var body: some View {
        NavigationView {
            content
                .navigationTitle("OpenDisk")
                .toolbar {
                    ToolbarItem(placement: .navigationBarLeading) {
                        Button(strings.settings) { settings = true }
                    }
                    ToolbarItem(placement: .navigationBarTrailing) {
                        Button {
                            adding = true
                        } label: {
                            Image(systemName: "plus")
                        }
                    }
                }
        }
        .navigationViewStyle(.stack)
        .task { await model.reload() }
        .sheet(isPresented: $adding) {
            AddCloudView(strings: strings, model: model)
        }
        .sheet(isPresented: $settings) {
            SettingsView(strings: strings, rcloneVersion: model.rcloneVersion)
        }
        .confirmationDialog(
            cloudToDelete.map(strings.deleteTitle) ?? "",
            isPresented: Binding(get: { cloudToDelete != nil }, set: { if !$0 { cloudToDelete = nil } }),
            titleVisibility: .visible
        ) {
            Button(strings.delete, role: .destructive) {
                if let name = cloudToDelete {
                    cloudToDelete = nil
                    Task { await model.delete(name) }
                }
            }
            Button(strings.cancel, role: .cancel) { cloudToDelete = nil }
        } message: {
            Text(strings.deleteExplanation)
        }
    }

    @ViewBuilder
    private var content: some View {
        if model.starting {
            ProgressView(strings.starting)
        } else {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    sectionTitle(strings.sectionDevice)
                    storageCard

                    sectionTitle(strings.sectionCategories)
                    categories

                    sectionTitle(strings.sectionBookmarks)
                    if files.bookmarks.isEmpty { hint(strings.noBookmarks) }
                    ForEach(files.bookmarks) { bookmark in bookmarkRow(bookmark) }

                    sectionTitle(strings.clouds)
                    if let error = model.error {
                        Text(error).foregroundColor(.red).padding(16)
                    }
                    if model.clouds.isEmpty {
                        hint(strings.noClouds)
                        hint(strings.unsignedBuildNotice)
                    }
                    ForEach(model.clouds) { cloud in cloudRow(cloud) }
                }
            }
            .refreshable { await model.reload() }
        }
    }

    // MARK: - Разделы

    private func sectionTitle(_ text: String) -> some View {
        Text(text)
            .font(.subheadline.weight(.semibold))
            .foregroundColor(.accentColor)
            .padding(.leading, 16)
            .padding(.top, 16)
            .padding(.bottom, 4)
    }

    private func hint(_ text: String) -> some View {
        Text(text)
            .font(.footnote)
            .foregroundColor(.secondary)
            .padding(.horizontal, 16)
            .padding(.vertical, 6)
    }

    /// Карточка хранилища с полосой заполнения — как на Android.
    private var storageCard: some View {
        let space = FilesModel.localSpace()
        return NavigationLink {
            FileScreen(strings: strings, location: Location(disk: .local))
        } label: {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 12) {
                    Text("📱").font(.title)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(strings.phoneStorage).font(.headline).foregroundColor(.primary)
                        if let space {
                            Text(strings.used(formatBytes(space.used), formatBytes(space.total)))
                                .font(.footnote)
                                .foregroundColor(.secondary)
                        }
                    }
                    Spacer()
                }
                if let space, space.total > 0 {
                    ProgressView(value: Double(space.used), total: Double(space.total))
                }
            }
            .padding(14)
            .background(Color.secondary.opacity(0.12))
            .cornerRadius(12)
            .padding(.horizontal, 12)
            .padding(.vertical, 4)
        }
        .buttonStyle(.plain)
    }

    private var categories: some View {
        let columns = Array(repeating: GridItem(.flexible(), spacing: 8), count: 3)
        return LazyVGrid(columns: columns, spacing: 8) {
            ForEach(FileCategory.allCases, id: \.self) { category in
                NavigationLink {
                    FileScreen(strings: strings, location: Location(disk: .local, path: "", category: category))
                } label: {
                    VStack(spacing: 4) {
                        Text(categoryGlyph(category)).font(.largeTitle)
                        Text(categoryTitle(category, strings)).font(.caption).foregroundColor(.primary).lineLimit(1)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
                    .background(categoryColor(category).opacity(0.16))
                    .cornerRadius(12)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 12)
    }

    private func bookmarkRow(_ bookmark: Bookmark) -> some View {
        let name = bookmark.path.split(separator: "/").last.map(String.init) ?? diskTitle(bookmark.disk, strings)
        let place = [diskTitle(bookmark.disk, strings), bookmark.path].filter { !$0.isEmpty }.joined(separator: " / ")
        return row(
            glyph: "🔖",
            title: name,
            subtitle: place,
            destination: FileScreen(strings: strings, location: Location(disk: bookmark.disk, path: bookmark.path))
        ) {
            Button(strings.removeBookmark) { files.removeBookmark(bookmark) }
        }
    }

    private func cloudRow(_ cloud: Cloud) -> some View {
        row(
            glyph: "☁",
            title: cloud.name,
            subtitle: describe(cloud.about) ?? "",
            destination: FileScreen(strings: strings, location: Location(disk: .cloud(cloud.name)))
        ) {
            Button(strings.delete, role: .destructive) { cloudToDelete = cloud.name }
        }
    }

    /// Строка списка: переход по нажатию и меню «⋮» рядом.
    private func row<Destination: View, Actions: View>(
        glyph: String,
        title: String,
        subtitle: String,
        destination: Destination,
        @ViewBuilder actions: () -> Actions
    ) -> some View {
        HStack(spacing: 0) {
            NavigationLink(destination: destination) {
                HStack(spacing: 12) {
                    Text(glyph).font(.title2).frame(width: 32)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title).font(.headline).foregroundColor(.primary).lineLimit(1)
                        if !subtitle.isEmpty {
                            Text(subtitle).font(.footnote).foregroundColor(.secondary).lineLimit(2)
                        }
                    }
                    Spacer()
                }
                .padding(.vertical, 10)
                .padding(.leading, 16)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            Menu {
                actions()
            } label: {
                Text("⋮").font(.title2).padding(.horizontal, 14).padding(.vertical, 8)
            }
        }
    }

    /// «занято 1,6 ГБ из 1,8 ГБ» или ничего, если бэкенд промолчал.
    private func describe(_ about: AboutInfo?) -> String? {
        guard let about else { return nil }
        switch (about.used, about.total) {
        case let (used?, total?):
            return strings.russian
                ? "занято \(formatBytes(used)) из \(formatBytes(total))"
                : "\(formatBytes(used)) of \(formatBytes(total)) used"
        case let (used?, nil):
            return strings.russian ? "занято \(formatBytes(used))" : "\(formatBytes(used)) used"
        case let (nil, total?):
            return strings.russian ? "всего \(formatBytes(total))" : "\(formatBytes(total)) total"
        default:
            return nil
        }
    }
}

private func categoryGlyph(_ category: FileCategory) -> String {
    switch category {
    case .images: return "🖼"
    case .video: return "🎞"
    case .audio: return "🎵"
    case .documents: return "📄"
    case .downloads: return "⬇"
    case .new: return "✨"
    }
}

/// Те же цвета, что у плиток категорий на Android.
private func categoryColor(_ category: FileCategory) -> Color {
    switch category {
    case .images: return Color(red: 0.26, green: 0.63, blue: 0.28)
    case .video: return Color(red: 0.90, green: 0.22, blue: 0.21)
    case .audio: return Color(red: 0.56, green: 0.14, blue: 0.67)
    case .documents: return Color(red: 0.12, green: 0.53, blue: 0.90)
    case .downloads: return Color(red: 0.0, green: 0.54, blue: 0.48)
    case .new: return Color(red: 0.95, green: 0.69, blue: 0.12)
    }
}
