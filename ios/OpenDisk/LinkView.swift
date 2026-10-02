import SwiftUI

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
