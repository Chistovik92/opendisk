import SwiftUI

/// Список облаков — первый и главный экран.
struct CloudListView: View {

    let strings: Strings

    @StateObject private var model = CloudsModel()
    @State private var adding = false
    @State private var settings = false
    @State private var cloudToDelete: String?

    var body: some View {
        NavigationView {
            content
                .navigationTitle(strings.clouds)
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
        } else if let error = model.error {
            ScrollView {
                Text(error).foregroundColor(.red).padding()
            }
        } else if model.clouds.isEmpty {
            ScrollView {
                VStack(spacing: 12) {
                    Text(strings.noClouds)
                    Text(strings.unsignedBuildNotice)
                        .font(.footnote)
                        .foregroundColor(.secondary)
                }
                .multilineTextAlignment(.center)
                .padding()
            }
        } else {
            List {
                ForEach(model.clouds) { cloud in
                    NavigationLink {
                        BrowserView(strings: strings, cloud: cloud.name, path: "")
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(cloud.name)
                            if let space = describe(cloud.about) {
                                Text(space).font(.footnote).foregroundColor(.secondary)
                            }
                        }
                    }
                    .swipeActions {
                        Button(strings.delete, role: .destructive) { cloudToDelete = cloud.name }
                    }
                }
            }
            .refreshable { await model.reload() }
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
