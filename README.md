# OpenDisk

Открытый кроссплатформенный клиент для монтирования облачных хранилищ как локального
виртуального диска — бесплатная альтернатива Disk-O / Air Explorer.

Работает на движке [rclone](https://rclone.org/) (70+ облачных провайдеров, включая
Яндекс.Диск, Mail.ru Cloud, Google Drive, Dropbox, S3, WebDAV/FTP) со своим нативным
GUI на **Kotlin / Compose Multiplatform**.

> Статус: ранняя стадия разработки. См. [ROADMAP.md](ROADMAP.md) для плана и текущего этапа.

## Возможности (план)

- Подключение облаков как локальных дисков, файлы скачиваются по требованию
  (on-demand, без полного зеркалирования на диск)
- Гибкая настройка кэширования (не скачивать всё, только то, с чем реально работаешь)
- Автозапуск и автоподключение дисков при входе в систему
- Поддержка Windows, Linux, macOS, Android и iOS (см. [ROADMAP.md](ROADMAP.md) —
  десктоп и мобильные платформы устроены по-разному, подробности в [ARCHITECTURE.md](docs/ARCHITECTURE.md))
- Полностью бесплатно, без подписок, исходный код открыт

## Как это устроено

Мы не переизобретаем протоколы облаков и виртуальные ФС — это уже отлично решено в
rclone (лицензия MIT) поверх FUSE (Linux), macFUSE (macOS) и WinFsp (Windows).
OpenDisk — это удобная оболочка поверх rclone: GUI, профили, автозапуск, трей.

Подробнее — в [ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Технологии

- **Kotlin** + **Compose Multiplatform for Desktop** — общий GUI-код на все ОС
- **rclone** — ядро работы с облаками и монтированием (запускается как подпроцесс)
- **Ktor client** — общение GUI с rclone через его RC (Remote Control) HTTP API

## Сборка из исходников

```bash
git clone https://github.com/<ваш-аккаунт>/opendisk.git
cd opendisk
./gradlew :composeApp:run
```

Требования: JDK 17+, установленный `rclone` в PATH (или он будет предложен к установке
при первом запуске).

## Участие в разработке

См. [CONTRIBUTING.md](CONTRIBUTING.md).

## Лицензия

[MIT](LICENSE) — как и у rclone, на котором построен проект.
