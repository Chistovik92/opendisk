# Первый запуск проекта

В репозитории уже есть `gradle/wrapper/gradle-wrapper.properties` (версия Gradle 8.10),
но бинарный `gradle-wrapper.jar` и скрипты `gradlew`/`gradlew.bat` нужно сгенерировать
один раз локально (в окружении, где готовился этот шаблон, не было доступа в интернет):

```bash
# Если Gradle уже установлен в системе (любой версии):
gradle wrapper --gradle-version 8.10

# Дальше уже используем локальный wrapper как обычно:
./gradlew build
./gradlew :composeApp:run
```

Если Gradle не установлен — поставь один раз через системный менеджер пакетов
(`epm install gradle` в ALT/Simply Linux, `sdk install gradle` через SDKMAN и т.п.),
выполни команду выше, и дальше он больше не понадобится — сборка идёт через `./gradlew`.

## Требования

- JDK 17 или новее
- rclone в PATH (для тестов и запуска приложения) — `epm install rclone`
