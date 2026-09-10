package com.opendisk.app

import java.util.Base64

/**
 * Сценарии PowerShell, которые приложение запускает на Windows.
 *
 * Лежат файлами `.ps1` в ресурсах (каталог `windows`), а не строками в коде. Причина —
 * проверка: CI запускает те же самые файлы на настоящей установке, и если бы
 * сценарий жил строкой здесь, проверялась бы его копия, которая рано или
 * поздно разошлась бы с оригиналом.
 *
 * Передаются через `-EncodedCommand`, а не `-File`: путь к установщику
 * содержит пробелы, а Java по-своему экранирует кавычки в командной строке —
 * на этом уже спотыкались и установка WinFsp, и автозапуск.
 */
internal object PowerShellScript {

    /**
     * Текст сценария из ресурсов, без комментариев.
     *
     * Комментарии срезаются не ради красоты: командная строка Windows
     * ограничена 32 тысячами знаков, а `-EncodedCommand` раздувает текст
     * почти втрое. Пояснения нужны читающему файл, а не PowerShell.
     */
    fun load(name: String): String {
        val text = PowerShellScript::class.java.getResourceAsStream("/windows/$name")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("в сборке нет сценария windows/$name")
        return text
            // Файлы сохранены с BOM — иначе Windows PowerShell 5.1 прочёл бы
            // кириллицу в комментариях как ANSI. Внутри команды он лишний.
            .removePrefix("﻿")
            .lineSequence()
            .filterNot { it.trimStart().startsWith("#") }
            .joinToString("\n")
    }

    /**
     * Вызов сценария с параметрами: `& { текст } -Имя 'значение'`.
     *
     * Значения в одинарных кавычках — внутри них PowerShell ничего не
     * подставляет; сама одинарная кавычка удваивается, иначе она оборвала
     * бы строку.
     */
    fun invocation(
        script: String,
        parameters: Map<String, String?> = emptyMap(),
        switches: List<String> = emptyList(),
    ): String = buildString {
        append("& {\n").append(script).append("\n}")
        parameters.forEach { (name, value) ->
            if (value != null) append(" -").append(name).append(" '").append(value.replace("'", "''")).append("'")
        }
        switches.forEach { append(" -").append(it) }
    }

    fun start(command: String): Process {
        val encoded = Base64.getEncoder().encodeToString(command.toByteArray(Charsets.UTF_16LE))
        return ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
            .start()
    }
}
