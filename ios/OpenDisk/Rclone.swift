import Foundation
import Librclone

/// Ошибка, пришедшая от самого rclone.
///
/// Текст берётся из его ответа, а не сочиняется: «что-то пошло не так» не
/// помогает никому, а «401 Unauthorized» или «no such host» говорят человеку
/// ровно то, что он может исправить.
struct RcloneError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

/// Файл или папка в облаке. Поля названы как в ответе rclone.
struct Entry: Decodable, Identifiable {
    let Path: String
    let Name: String
    let Size: Int64
    let IsDir: Bool

    var id: String { Path }
}

/// Занятое и свободное место. Не каждый бэкенд это сообщает — отсюда
/// необязательные поля.
struct AboutInfo: Decodable {
    let total: Int64?
    let used: Int64?
    let free: Int64?
}

/// Что умеет бэкенд конкретного облака.
struct FsInfo: Decodable {
    let Features: [String: Bool]

    var supportsPublicLink: Bool { Features["PublicLink"] == true }
}

/// Вызовы rclone внутри процесса.
///
/// На iPhone, как и на Android, отдельный процесс `rclone rcd` поднять нельзя:
/// система не даёт приложению исполнять свои файлы. Официальный путь тот же —
/// librclone: та же RC API, только вызовом функции внутри процесса.
///
/// Разбор ответов здесь свой, на Swift, а не общий с Kotlin: делить код между
/// JVM и iOS можно было бы только через Kotlin/Native, а это отдельный тулчейн
/// ради полутора сотен строк разбора JSON. Общей остаётся сама RC API —
/// то есть имена методов и форма ответов.
actor Rclone {

    static let shared = Rclone()

    private var started = false

    /// Файл со списком облаков. Лежит в «Документах» приложения, поэтому его
    /// видно в «Файлах»: конфиг с компьютера кладётся туда же вручную.
    static var configURL: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("rclone.conf")
    }

    private func start() {
        guard !started else { return }
        // rclone читает путь к конфигу из окружения — задаём до инициализации,
        // иначе библиотека уже выберет путь по умолчанию, недоступный на iOS.
        setenv("RCLONE_CONFIG", Rclone.configURL.path, 1)
        GomobileRcloneInitialize()
        started = true
    }

    /// Один вызов RC API: метод плюс объект на входе, объект на выходе.
    func call(_ method: String, _ input: [String: Any] = [:]) throws -> Data {
        start()
        let body = try JSONSerialization.data(withJSONObject: input)
        guard let result = GomobileRcloneRPC(method, String(data: body, encoding: .utf8) ?? "{}") else {
            throw RcloneError(message: "rclone не ответил на \(method)")
        }
        // `as String?` намеренно: gomobile размечает поля по-разному от версии
        // к версии, и так код одинаково переживает и обязательное, и
        // необязательное поле.
        let output = result.output as String? ?? ""
        guard result.status == 200 else {
            throw RcloneError(message: Rclone.describe(output, status: result.status, method: method))
        }
        return Data(output.utf8)
    }

    private func decode<T: Decodable>(_ type: T.Type, _ method: String, _ input: [String: Any] = [:]) throws -> T {
        try JSONDecoder().decode(type, from: call(method, input))
    }

    /// Текст ошибки rclone отдаёт полем `error` в теле ответа.
    private static func describe(_ output: String, status: Int, method: String) -> String {
        if let data = output.data(using: .utf8),
           let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let error = object["error"] as? String {
            return error
        }
        return "\(method): rclone ответил \(status)"
    }

    // MARK: - Облака

    private struct RemotesResponse: Decodable { let remotes: [String]? }

    func remotes() throws -> [String] {
        try decode(RemotesResponse.self, "config/listremotes").remotes ?? []
    }

    func about(_ remote: String) throws -> AboutInfo {
        try decode(AboutInfo.self, "operations/about", ["fs": "\(remote):"])
    }

    func fsInfo(_ remote: String) throws -> FsInfo {
        try decode(FsInfo.self, "operations/fsinfo", ["fs": "\(remote):"])
    }

    private struct ListResponse: Decodable { let list: [Entry]? }

    func list(_ remote: String, path: String = "") throws -> [Entry] {
        let entries = try decode(
            ListResponse.self,
            "operations/list",
            ["fs": "\(remote):", "remote": path]
        ).list ?? []
        // Папки вперёд, дальше по алфавиту: rclone порядка не обещает.
        return entries.sorted {
            $0.IsDir == $1.IsDir
                ? $0.Name.lowercased() < $1.Name.lowercased()
                : $0.IsDir
        }
    }

    private struct LinkResponse: Decodable { let url: String }

    func publicLink(_ remote: String, path: String) throws -> String {
        try decode(LinkResponse.self, "operations/publiclink", ["fs": "\(remote):", "remote": path]).url
    }

    private struct ObscureResponse: Decodable { let obscured: String }

    /// Пароли rclone хранит «затемнёнными» и открытый текст в конфиге не примет.
    func obscure(_ clear: String) throws -> String {
        try decode(ObscureResponse.self, "core/obscure", ["clear": clear]).obscured
    }

    func createRemote(name: String, type: String, parameters: [String: String]) throws {
        _ = try call(
            "config/create",
            ["name": name, "type": type, "parameters": parameters, "opt": ["nonInteractive": true]]
        )
    }

    func deleteRemote(_ name: String) throws {
        _ = try call("config/delete", ["name": name])
    }

    private struct VersionResponse: Decodable { let version: String }

    func version() throws -> String {
        try decode(VersionResponse.self, "core/version").version
    }
}

/// Человекочитаемый размер. rclone отдаёт байты; у папок размер приходит
/// как -1 — это «неизвестно», а не ноль.
func formatBytes(_ bytes: Int64) -> String {
    guard bytes >= 0 else { return "" }
    let formatter = ByteCountFormatter()
    formatter.countStyle = .binary
    return formatter.string(fromByteCount: bytes)
}
