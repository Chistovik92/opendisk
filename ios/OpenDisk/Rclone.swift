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

/// Бэкенд rclone и его поля — из `config/providers`.
struct Provider: Decodable {
    struct Example: Decodable {
        let Value: String
        let Help: String
    }

    struct Option: Decodable {
        let Name: String
        let Help: String
        let Required: Bool
        let IsPassword: Bool
        let Advanced: Bool
        let provider: String?
        let Examples: [Example]?

        enum CodingKeys: String, CodingKey {
            case Name, Help, Required, IsPassword, Advanced, Examples
            case provider = "Provider"
        }

        /// Относится ли поле к варианту бэкенда — провайдеру S3 и т. п.
        func applies(to variant: String?) -> Bool {
            guard let variant, let provider, !provider.isEmpty else { return true }
            let negate = provider.hasPrefix("!")
            let listed = provider.drop(while: { $0 == "!" }).split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }
            return listed.contains(variant) != negate
        }
    }

    let Name: String
    let Description: String
    let Prefix: String?
    let Options: [Option]

    /// То, что пишется в `type` конфига: у «google photos» это `gphotos`.
    var type: String { (Prefix?.isEmpty == false ? Prefix : nil) ?? Name }
}

/// Журнал rclone, работающего внутри приложения.
///
/// Нужен ради ссылки входа через браузер: rclone печатает её в свой журнал
/// и ждёт, пока человек подтвердит доступ. Журнал он пишет в поток ошибок —
/// дескриптор 2; gomobile на iOS этот дескриптор не трогает. Приложение
/// ставит на него свой канал, находит ссылку и открывает окно входа.
/// Всё прочитанное повторяется в прежний поток ошибок, чтобы вывод rclone
/// не пропал из консоли Xcode.
final class RcloneLog: @unchecked Sendable {

    static let shared = RcloneLog()

    private let lock = NSLock()
    private var recent: [String] = []
    private var listeners: [UUID: (String) -> Void] = [:]
    private var capturing = false

    func capture() {
        lock.lock()
        defer { lock.unlock() }
        guard !capturing else { return }

        var fds: [Int32] = [0, 0]
        guard pipe(&fds) == 0 else { return }
        let original = dup(STDERR_FILENO)
        dup2(fds[1], STDERR_FILENO)
        close(fds[1])
        capturing = true

        let reader = fds[0]
        let thread = Thread { [weak self] in
            var buffer = Data()
            var chunk = [UInt8](repeating: 0, count: 4096)
            while true {
                let count = read(reader, &chunk, chunk.count)
                if count <= 0 { break }
                if original >= 0 { _ = chunk.withUnsafeBytes { write(original, $0.baseAddress, count) } }
                buffer.append(chunk, count: count)
                while let newline = buffer.firstIndex(of: 0x0A) {
                    let line = String(decoding: buffer[buffer.startIndex..<newline], as: UTF8.self)
                    buffer.removeSubrange(buffer.startIndex...newline)
                    self?.deliver(line)
                }
            }
        }
        thread.name = "rclone-log"
        thread.start()
    }

    func recentLines() -> [String] {
        lock.lock()
        defer { lock.unlock() }
        return recent
    }

    @discardableResult
    func addListener(_ listener: @escaping (String) -> Void) -> UUID {
        lock.lock()
        defer { lock.unlock() }
        let id = UUID()
        listeners[id] = listener
        return id
    }

    func removeListener(_ id: UUID) {
        lock.lock()
        defer { lock.unlock() }
        listeners[id] = nil
    }

    private func deliver(_ line: String) {
        lock.lock()
        recent.append(line)
        if recent.count > 200 { recent.removeFirst(recent.count - 200) }
        let current = Array(listeners.values)
        lock.unlock()
        current.forEach { $0(line) }
    }
}

/// Ссылка подтверждения входа через браузер. Та же логика, что в
/// `rclone-bridge/.../OAuthLink.kt` на компьютере и Android.
enum OAuthLink {

    static func find(in line: String) -> String? {
        guard let range = line.range(of: #"http://127\.0\.0\.1:\d+/auth\?state=[\w\-.~%]+"#, options: .regularExpression) else {
            return nil
        }
        return String(line[range])
    }

    /// Висящий `config/create` сам не отменяется: rclone ждёт возврата из
    /// браузера без срока. Но его сервер принимает отказ — ровно в том виде,
    /// в каком его прислал бы сервис. Нужен тот же `state`, что в ссылке.
    static func cancel(_ link: String) {
        guard let components = URLComponents(string: link),
              let state = components.queryItems?.first(where: { $0.name == "state" })?.value,
              let host = components.host, let port = components.port,
              let url = URL(string: "http://\(host):\(port)/?state=\(state)&error=access_denied")
        else { return }
        URLSession.shared.dataTask(with: url).resume()
    }
}

/// Вызовы rclone внутри процесса.
///
/// На iPhone, как и на Android, отдельный процесс `rclone rcd` поднять нельзя:
/// система не даёт приложению исполнять свои файлы. Официальный путь тот же —
/// librclone: та же RC API, только вызовом функции внутри процесса.
///
/// Вызовы не сериализуются: `config/create` при входе через браузер висит,
/// пока человек подтверждает доступ, и остальные запросы в это время должны
/// идти как обычно. librclone к параллельным вызовам готова.
final class Rclone: @unchecked Sendable {

    static let shared = Rclone()

    /// Файл со списком облаков. Лежит в «Документах» приложения, поэтому его
    /// видно в «Файлах»: конфиг с компьютера кладётся туда же вручную.
    static var configURL: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("rclone.conf")
    }

    /// Запуск один на процесс. Путь к конфигу rclone читает из окружения при
    /// инициализации — поэтому до неё.
    private static let started: Void = {
        setenv("RCLONE_CONFIG", Rclone.configURL.path, 1)
        GomobileRcloneInitialize()
        RcloneLog.shared.capture()
    }()

    private func callSync(_ method: String, _ input: [String: Any]) throws -> Data {
        _ = Rclone.started
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

    /// Один вызов RC API: метод плюс объект на входе, объект на выходе.
    /// Уходит с главного потока — некоторые вызовы висят минутами.
    func call(_ method: String, _ input: [String: Any] = [:]) async throws -> Data {
        let box = InputBox(input)
        return try await Task.detached(priority: .userInitiated) {
            try self.callSync(method, box.value)
        }.value
    }

    private func decode<T: Decodable>(_ type: T.Type, _ method: String, _ input: [String: Any] = [:]) async throws -> T {
        try JSONDecoder().decode(type, from: try await call(method, input))
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

    func remotes() async throws -> [String] {
        try await decode(RemotesResponse.self, "config/listremotes").remotes ?? []
    }

    func about(_ remote: String) async throws -> AboutInfo {
        try await decode(AboutInfo.self, "operations/about", ["fs": "\(remote):"])
    }

    func fsInfo(_ remote: String) async throws -> FsInfo {
        try await decode(FsInfo.self, "operations/fsinfo", ["fs": "\(remote):"])
    }

    private struct ListResponse: Decodable { let list: [Entry]? }

    func list(_ remote: String, path: String = "") async throws -> [Entry] {
        let entries = try await decode(
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

    func publicLink(_ remote: String, path: String) async throws -> String {
        try await decode(LinkResponse.self, "operations/publiclink", ["fs": "\(remote):", "remote": path]).url
    }

    private struct ObscureResponse: Decodable { let obscured: String }

    /// Пароли rclone хранит «затемнёнными» и открытый текст в конфиге не примет.
    func obscure(_ clear: String) async throws -> String {
        try await decode(ObscureResponse.self, "core/obscure", ["clear": clear]).obscured
    }

    /// Создаёт облако. Для сервисов со входом через браузер висит, пока
    /// человек подтверждает доступ, — так же, как на компьютере.
    func createRemote(name: String, type: String, parameters: [String: String]) async throws {
        _ = try await call("config/create", ["name": name, "type": type, "parameters": parameters])
    }

    func deleteRemote(_ name: String) async throws {
        _ = try await call("config/delete", ["name": name])
    }

    private struct ProvidersResponse: Decodable { let providers: [Provider] }

    func providers() async throws -> [Provider] {
        try await decode(ProvidersResponse.self, "config/providers").providers
    }

    private struct VersionResponse: Decodable { let version: String }

    func version() async throws -> String {
        try await decode(VersionResponse.self, "core/version").version
    }
}

/// Словарь с разнородными значениями не `Sendable`, но передаётся он в
/// отдельную задачу один раз и больше никем не меняется.
private struct InputBox: @unchecked Sendable {
    let value: [String: Any]
    init(_ value: [String: Any]) { self.value = value }
}

/// Человекочитаемый размер. rclone отдаёт байты; у папок размер приходит
/// как -1 — это «неизвестно», а не ноль.
func formatBytes(_ bytes: Int64) -> String {
    guard bytes >= 0 else { return "" }
    let formatter = ByteCountFormatter()
    formatter.countStyle = .binary
    return formatter.string(fromByteCount: bytes)
}
