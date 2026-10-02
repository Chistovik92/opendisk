import Foundation

/// Файл или папка в облаке. Поля названы как в ответе rclone.
struct Entry: Decodable, Identifiable, Hashable {
    let Path: String
    let Name: String
    let Size: Int64
    let IsDir: Bool
    /// Время изменения, ISO 8601; не каждый бэкенд его сообщает.
    var ModTime: String? = nil

    var id: String { Path }
}
