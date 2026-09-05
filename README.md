# OpenDisk

Открытый кроссплатформенный клиент для монтирования облачных хранилищ как локального
виртуального диска — бесплатная альтернатива Disk-O / Air Explorer.

Работает на движке [rclone](https://rclone.org/) (70+ облачных провайдеров, включая
Яндекс.Диск, Mail.ru Cloud, Google Drive, Dropbox, S3, WebDAV/FTP) со своим нативным
GUI на **Kotlin / Compose Multiplatform**.

> Статус: ранняя стадия разработки. См. [ROADMAP.md](ROADMAP.md) для плана и текущего этапа.

## Установка

### Linux — одной командой

```bash
curl -fsSL https://raw.githubusercontent.com/Chistovik92/opendisk/main/scripts/install.sh | sh
```

Скрипт сам определит пакетный менеджер, скачает подходящий пакет из релизов,
сверит контрольную сумму и установит. Потребуются права администратора.

Если запускать чужой скрипт из интернета не хочется — и это здравая
осторожность, — сделайте то же в два шага:

```bash
curl -fsSL -o install.sh https://raw.githubusercontent.com/Chistovik92/opendisk/main/scripts/install.sh
less install.sh   # посмотреть, что он делает
sh install.sh
```

Конкретную версию можно задать явно:

```bash
OPENDISK_VERSION=v0.1.11 sh install.sh
```

Либо скачать пакет вручную со страницы
[релизов](https://github.com/Chistovik92/opendisk/releases) — там лежат `.deb`
и `.rpm`.

**Для подключения облаков как дисков нужен FUSE** — его придётся поставить
отдельно, это модуль ядра: `sudo apt install fuse3` или `sudo dnf install fuse3`.
Скрипт проверит и напомнит.

### Windows

Скачайте `.msi` со страницы [релизов](https://github.com/Chistovik92/opendisk/releases)
и запустите. Нужны права администратора. Драйвер файловой системы WinFsp входит
в состав — приложение предложит установить его по кнопке.

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

Требования: JDK 17 или 21 (Gradle 8.10 не запускается на JDK 25/26). Ставить `rclone`
отдельно **не нужно** — он скачивается на этапе сборки и едет внутри дистрибутива.
Подробнее — в [docs/SETUP.md](docs/SETUP.md).

## Участие в разработке

См. [CONTRIBUTING.md](CONTRIBUTING.md).

## Лицензия

[MIT](LICENSE) — как и у rclone, на котором построен проект.

OpenDisk распространяет бинарник rclone в составе дистрибутива; его лицензия —
[licenses/rclone-LICENSE.txt](licenses/rclone-LICENSE.txt).
