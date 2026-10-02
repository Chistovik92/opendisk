import BackgroundTasks
import Foundation

/// Проверка токенов облаков в фоне — зеркало TokenRefreshWorker.kt на Android.
///
/// rclone обновляет токен доступа сам, но только когда им пользуются. Если
/// облако не открывали неделями, у сервисов с токеном «пока пользуются»
/// срок мог выйти, и человек узнавал об этом по файлу, который не открылся.
/// Лёгкий запрос раз в сутки держит такой токен живым. Google в режиме
/// тестирования это не лечит — токен там умирает через неделю в любом
/// случае, — зато об этом узнают сразу: облако помечается «нужно войти
/// заново».
///
/// iOS запускает фоновую задачу, когда сочтёт нужным, и ничего не обещает:
/// это «по возможности», а не «ровно раз в сутки». Задачу ставит приложение
/// при уходе в фон, а регистрируется она до конца запуска (OpenDiskApp.init).
enum TokenRefresh {

    static let identifier = "com.opendisk.ios.token-refresh"
    private static let defaultsKey = "needsSignIn"

    /// Облака, у которых истёк доступ. Помнится между запусками: проверка идёт в
    /// фоне, а человек должен увидеть «войти заново», как только откроет приложение.
    static var needsSignIn: Set<String> {
        get { Set(UserDefaults.standard.stringArray(forKey: defaultsKey) ?? []) }
        set { UserDefaults.standard.set(Array(newValue).sorted(), forKey: defaultsKey) }
    }

    /// Регистрирует задачу. Звать нужно до конца запуска приложения.
    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: nil) { task in
            guard let task = task as? BGAppRefreshTask else { return }
            // Следующий запуск ставим сразу: иначе задача сработала бы один раз.
            schedule()
            let work = Task {
                await check()
                task.setTaskCompleted(success: true)
            }
            task.expirationHandler = { work.cancel() }
        }
    }

    /// Просит систему запустить проверку не раньше чем через сутки.
    static func schedule() {
        let request = BGAppRefreshTaskRequest(identifier: identifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 24 * 60 * 60)
        // Отказ (нет разрешения на фон, симулятор) — не ошибка: проверка просто не пойдёт.
        try? BGTaskScheduler.shared.submit(request)
    }

    private enum Probe { case ok, expired, other }

    /// Обращается к каждому облаку и обновляет список «нужно войти заново».
    static func check() async {
        guard let remotes = try? await Rclone.shared.remotes() else { return }
        var expired = Set<String>()
        var unreachable = Set<String>()
        for name in remotes {
            if Task.isCancelled { return }
            switch await probe(name) {
            case .expired: expired.insert(name)
            case .other: unreachable.insert(name)
            case .ok: break
            }
        }
        needsSignIn = mergeNeedsSignIn(
            before: needsSignIn,
            existing: Set(remotes),
            expired: expired,
            unreachable: unreachable
        )
    }

    /// Лёгкий запрос к облаку. `about` у части бэкендов не поддерживается —
    /// тогда читаем корень: ошибка про токен приходит и на нём.
    private static func probe(_ name: String) async -> Probe {
        do {
            _ = try await Rclone.shared.about(name)
            return .ok
        } catch {
            do {
                _ = try await Rclone.shared.list(fs: "\(name):")
                return .ok
            } catch {
                return AuthErrors.isExpired(error.localizedDescription) ? .expired : .other
            }
        }
    }
}
