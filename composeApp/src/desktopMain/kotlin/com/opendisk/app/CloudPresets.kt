package com.opendisk.app

import androidx.compose.ui.graphics.Color
import com.opendisk.bridge.CatalogGroup
import com.opendisk.bridge.CatalogService
import com.opendisk.bridge.CloudCatalog

/**
 * Готовое подключение к сервису — плитка в мастере.
 *
 * Сами сервисы живут в общем каталоге [CloudCatalog]: он один на компьютер
 * и телефоны, и до 0.5.4 его отсутствие стоило того, что на Android не было
 * Google Диска. Здесь каталог только переводится в то, что умеет рисовать
 * Compose на десктопе: цвет плитки и тексты на выбранном языке.
 */
data class CloudPreset(
    val id: String,
    val title: String,
    /** Короткое пояснение под названием на плитке. */
    val subtitle: String,
    /** Тип бэкенда rclone. */
    val backend: String,
    /** Параметры, которые подставляются сами и пользователю не показываются. */
    val fixed: Map<String, String> = emptyMap(),
    /** Поля, которые нужно спросить. Для OAuth-сервисов обычно пусто. */
    val fields: List<PresetField> = emptyList(),
    /** Требуется ли подтверждение доступа в браузере. */
    val oauth: Boolean = false,
    /** Подсказка над формой — например, про пароль для внешних приложений. */
    val hint: String? = null,
    /** Цвет плитки. Собственный, не фирменный: логотипы сервисов мы не копируем. */
    val accent: Color,
    /** Символ на плитке. */
    val glyph: String,
    val group: CatalogGroup,
    /** Сервис из каталога — для поиска по другим названиям и странам. */
    val service: CatalogService,
)

/**
 * Спросит ли сервис, под каким аккаунтом входить. Где адрес авторизации свой,
 * там страница открывается в уже открытом аккаунте молча, и об этом нужно
 * предупредить заранее — иначе второй диск незаметно окажется первым.
 */
val CloudPreset.asksWhichAccount: Boolean
    get() = service.asksWhichAccount

/**
 * Похоже ли значение на идентификатор приложения Google.
 *
 * Проверка нужна до того, как открывать браузер. Иначе неверное значение
 * выясняется только на странице Google — она отвечает «Доступ заблокирован,
 * ошибка 401: invalid_client, The OAuth client was not found», а приложение
 * при этом остаётся ждать подтверждения, которого уже не будет.
 *
 * Пустое значение допустимо: это означает «взять встроенный идентификатор
 * rclone» — медленный, но рабочий.
 *
 * Google выдаёт идентификаторы вида `202264815644.apps.googleusercontent.com`;
 * окончание одинаковое у всех, по нему и отличаем от опечатки, номера проекта
 * или случайно вставленного секрета.
 */
fun looksLikeGoogleClientId(value: String): Boolean {
    val trimmed = value.trim()
    return trimmed.isEmpty() || trimmed.endsWith(GOOGLE_CLIENT_ID_SUFFIX)
}

const val GOOGLE_CLIENT_ID_SUFFIX = ".apps.googleusercontent.com"

data class PresetField(
    val key: String,
    val label: String,
    val isPassword: Boolean = false,
    val required: Boolean = true,
    val help: String? = null,
)

/** Плитки на языке интерфейса — из общего каталога. */
fun cloudPresets(strings: Strings): List<CloudPreset> =
    CloudCatalog.services.map { it.toPreset(strings.russian) }

fun CatalogService.toPreset(russian: Boolean): CloudPreset = CloudPreset(
    id = id,
    title = title.pick(russian),
    subtitle = subtitle.pick(russian),
    backend = backend,
    fixed = fixed,
    fields = fields.map { field ->
        PresetField(
            key = field.key,
            label = field.label.pick(russian),
            isPassword = field.isPassword,
            required = field.required,
            help = field.help?.pick(russian),
        )
    },
    oauth = oauth,
    hint = hint?.pick(russian),
    accent = Color(accent),
    glyph = glyph,
    group = group,
    service = this,
)
