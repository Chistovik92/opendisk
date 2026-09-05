# Первый запуск проекта

## Требования

- **JDK 17 или 21.** Это важно: wrapper запинен на Gradle 8.10, а он не умеет
  запускаться на JDK 25/26 — поддержка Java 26 появилась только в Gradle 9.4,
  который, в свою очередь, несовместим с Kotlin 2.0.20 и Compose 1.7.0.
  Компилируется проект в любом случае под таргет 17 (`jvmToolchain(17)`).
- **Доступ в интернет при первой сборке** — Gradle скачает сам себя и rclone.
  Отдельно ставить rclone не нужно.

## Сборка

```bash
./gradlew build
./gradlew :composeApp:run
```

Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/`) лежит в репозитории,
устанавливать Gradle отдельно не нужно — при первом запуске он сам скачает
нужный дистрибутив.

## Сборка установщика под Windows

```bash
./gradlew :composeApp:packageMsi
```

Результат — `composeApp/build/compose/binaries/main/msi/OpenDisk-<версия>.msi`
(около 92 МБ: внутри JRE и встроенный rclone). WiX ставить не нужно — плагин
Compose скачивает и распаковывает его сам (задача `unzipWix`).

Портативный вариант без установщика:

```bash
./gradlew :composeApp:createDistributable
```

Он кладёт готовое приложение в `composeApp/build/compose/binaries/main/app/OpenDisk/`,
запускать — `OpenDisk.exe`.

Блок `windows { }` в `nativeDistributions` задаёт три вещи, без которых
установщик получается неудобным, и все три проверены на реальной установке:

- `menu` / `shortcut` — без них jpackage не создаёт **никаких** ярлыков, и после
  установки приложение запускается только вручную из `Program Files`;
- `upgradeUuid` — закреплён явно. Без фиксации новая версия рискует встать
  рядом со старой вместо обновления; значение взято то самое, которое jpackage
  вывел из имени пакета для 0.1.5, чтобы не сломать обновление с неё.

Установщик машинный (`ALLUSERS=1`), то есть требует прав администратора и ставит
в `Program Files`. Без повышения прав `msiexec` падает с кодом 1603 и ошибкой 1402
(отказ записи в `HKEY_LOCAL_MACHINE`).

> **Только ASCII в метаданных установщика.** WiX собирает MSI в кодовой странице
> 1252 и падает с `LGHT0311` на кириллице в `description`/`vendor`. Поэтому
> описание в `nativeDistributions` английское, хотя интерфейс русский.

### Про 32-битную сборку

Её нет и быть не может:

- Skiko (движок отрисовки Compose) публикует нативные библиотеки только для
  `windows-x64` и `windows-arm64` — сборки под x86 не существует;
- 32-битных JDK новее восьмой версии не выпускают, а jpackage требует JDK
  той же разрядности, что и целевая платформа.

## Тесты

```bash
./gradlew test
```

Тесты `rclone-bridge` мокают HTTP-ответы и настоящий rclone не требуют. Отдельно
есть `RcloneIntegrationTest` — он поднимает реальный `rclone rcd` и проверяет, что
формы ответов RC API те, на которые рассчитан клиент. По умолчанию он пропускается;
чтобы прогнать, укажите бинарник (после сборки он лежит в ресурсах приложения):

```bash
./gradlew test -Dopendisk.rclone.path="$PWD/composeApp/build/appResources/common/rclone"
```

## Встроенный rclone

rclone не ставится в систему отдельно — он едет внутри OpenDisk. Задача
`:composeApp:downloadRclone` на этапе сборки скачивает официальный архив с
`downloads.rclone.org`, сверяет SHA-256 с суммами, зафиксированными в
`composeApp/build.gradle.kts`, и кладёт бинарник в ресурсы приложения. Она
подцеплена к `prepareAppResources`, поэтому отрабатывает и при
`./gradlew :composeApp:run`, и при сборке установщика — вручную вызывать не нужно.

Обновление версии rclone: поменяйте `rcloneVersion` и суммы в `rcloneChecksums`,
сверив их с `https://downloads.rclone.org/v<version>/SHA256SUMS`.

Собрать без скачивания (офлайн, CI без сети) — приложение тогда будет искать
rclone в `PATH`:

```bash
./gradlew build -Popendisk.skipRcloneDownload=true
```

Подсунуть свой бинарник вместо встроенного:

```bash
OPENDISK_RCLONE=/path/to/rclone ./gradlew :composeApp:run
```

## Если в системе несколько JDK

Проверить, на чём запустится сборка:

```bash
java -version
```

Если это не 17 и не 21 — укажите нужный JDK явно через `JAVA_HOME`:

```bash
# Linux / macOS
JAVA_HOME=/path/to/jdk-21 ./gradlew build
```

```powershell
# Windows PowerShell
$env:JAVA_HOME = "C:\Users\<user>\.jdks\jdk-21"; .\gradlew.bat build
```

Чтобы не указывать это каждый раз, можно прописать путь в `~/.gradle/gradle.properties`
(файл вне репозитория, поэтому не мешает остальным):

```properties
org.gradle.java.home=/path/to/jdk-21
```

## Раскладка исходников

Модуль `:composeApp` собирается плагином `kotlin("jvm")`, который по умолчанию
видит только `src/main/kotlin`. Каталоги `src/commonMain/kotlin` и
`src/desktopMain/kotlin` (см. [ARCHITECTURE.md](ARCHITECTURE.md)) подключены
вручную в `composeApp/build.gradle.kts`. Если в будущем модуль переедет на
`kotlin("multiplatform")`, этот блок `sourceSets` нужно будет убрать.
