import SwiftUI

/// Содержимое папки в облаке.
///
/// Вглубь — обычным переходом навигации, потому что «назад» на iPhone должно
/// работать привычным жестом, а не своей кнопкой.
struct BrowserView: View {

    let strings: Strings
    let cloud: String
    let path: String

    @State private var entries: [Entry] = []
    @State private var loading = true
    @State private var error: String?
    @State private var link: LinkState?

    var body: some View {
        Group {
            if loading {
                ProgressView(strings.readingFolder)
            } else if let error {
                ScrollView { Text(error).foregroundColor(.red).padding() }
            } else if entries.isEmpty {
                Text(strings.emptyFolder).foregroundColor(.secondary)
            } else {
                List(entries) { entry in
                    if entry.IsDir {
                        NavigationLink {
                            BrowserView(strings: strings, cloud: cloud, path: entry.Path)
                        } label: {
                            Text(entry.Name)
                        }
                    } else {
                        Button {
                            askLink(for: entry)
                        } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(entry.Name).foregroundColor(.primary)
                                Text(formatBytes(entry.Size))
                                    .font(.footnote)
                                    .foregroundColor(.secondary)
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle(path.isEmpty ? cloud : String(path.split(separator: "/").last ?? ""))
        .task { await load() }
        .sheet(item: $link) { state in
            LinkView(strings: strings, state: state)
        }
    }

    private func load() async {
        do {
            entries = try await Rclone.shared.list(cloud, path: path)
            error = nil
        } catch {
            self.error = error.localizedDescription
        }
        loading = false
    }

    private func askLink(for entry: Entry) {
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

struct LinkState: Identifiable {
    let remotePath: String
    let url: String?
    let error: String?

    var id: String { remotePath + (url ?? "") + (error ?? "") }
}

/// Ссылку выдаёт сам сервис — приложение только просит и показывает.
struct LinkView: View {

    let strings: Strings
    let state: LinkState

    @Environment(\.dismiss) private var dismiss
    @State private var copied = false

    var body: some View {
        NavigationView {
            VStack(alignment: .leading, spacing: 14) {
                Text(state.remotePath).font(.footnote).foregroundColor(.secondary)

                if let error = state.error {
                    Text(error).foregroundColor(.red)
                } else if let url = state.url {
                    Text(url).textSelection(.enabled)
                    Text(strings.linkExplanation).font(.footnote).foregroundColor(.secondary)
                    HStack(spacing: 12) {
                        Button(strings.copy) {
                            UIPasteboard.general.string = url
                            copied = true
                        }
                        if copied {
                            Text(strings.copied).font(.footnote).foregroundColor(.secondary)
                        }
                    }
                } else {
                    ProgressView(strings.linkAsking)
                }
                Spacer()
            }
            .padding()
            .navigationTitle(strings.linkTitle)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(strings.close) { dismiss() }
                }
            }
        }
        .navigationViewStyle(.stack)
    }
}
