package com.opendisk.bridge

/** Текст на двух языках интерфейса. */
data class Localized(val ru: String, val en: String) {
    fun pick(russian: Boolean): String = if (russian) ru else en
}

/** Поле, которое спрашиваем у человека при добавлении облака. */
data class CatalogField(
    val key: String,
    val label: Localized,
    val isPassword: Boolean = false,
    val required: Boolean = true,
    val help: Localized? = null,
)

/** Раздел списка сервисов. Порядок разделов — порядок показа. */
enum class CatalogGroup(val title: Localized) {
    BROWSER(Localized("Вход через браузер", "Sign in via browser")),
    PASSWORD(Localized("Вход по паролю", "Sign in with a password")),
    OBJECT_STORAGE(Localized("Объектные хранилища", "Object storage")),
    SERVERS(Localized("Свои серверы и протоколы", "Own servers and protocols")),
    ALL(Localized("Все сервисы rclone", "All rclone services")),
}

/**
 * Сервис, к которому можно подключиться.
 *
 * Смысл в том, чтобы человеку не приходилось знать, что Яндекс.Диск — это
 * бэкенд `yandex`, Cloudflare R2 — это S3 с провайдером `Cloudflare`, а
 * Mail.ru просит не обычный пароль, а пароль для внешних приложений.
 */
data class CatalogService(
    val id: String,
    val title: Localized,
    /** Короткое пояснение под названием. */
    val subtitle: Localized,
    /** Тип бэкенда rclone. */
    val backend: String,
    /** Параметры, которые подставляются сами и не показываются. */
    val fixed: Map<String, String> = emptyMap(),
    /** Поля, которые нужно спросить. Для сервисов со входом через браузер обычно пусто. */
    val fields: List<CatalogField> = emptyList(),
    /** Доступ подтверждается в браузере, а не паролем. */
    val oauth: Boolean = false,
    /** Подсказка над формой — например, про пароль для внешних приложений. */
    val hint: Localized? = null,
    /** Цвет плитки, 0xAARRGGBB. Свой, не фирменный: логотипы сервисов мы не копируем. */
    val accent: Long,
    /** Символ на плитке. */
    val glyph: String,
    val group: CatalogGroup,
    /** Другие названия для поиска: страна, старое имя, как сервис зовут в обиходе. */
    val keywords: List<String> = emptyList(),
) {
    /**
     * Спросит ли сервис, под каким аккаунтом входить.
     *
     * Определяется тем, переопределён ли адрес авторизации: переопределяем мы
     * его ровно ради параметра выбора аккаунта (`force_confirm`,
     * `force_reapprove`, `prompt=select_account` — у каждого сервиса свой).
     */
    val asksWhichAccount: Boolean get() = oauth && "auth_url" in fixed

    fun matches(query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return listOf(title.ru, title.en, subtitle.ru, subtitle.en, backend)
            .plus(keywords)
            .any { it.contains(q, ignoreCase = true) }
    }
}

/**
 * Каталог сервисов — один на компьютер и телефоны.
 *
 * До 0.5.4 плитки жили отдельно на десктопе и на Android и расходились:
 * на телефоне было пять сервисов, на компьютере восемь, Google Диска на
 * телефоне не было вовсе. Теперь список один, а у каждого сервиса сразу
 * видно, как в него входят.
 *
 * Две части. Сверху — отобранные вручную сервисы с готовыми адресами и
 * человеческими подсказками. Снизу — [fromProviders]: всё, что знает
 * встроенный rclone, построенное из его же `config/providers`. Эта часть
 * не устаревает: новый бэкенд в rclone сам появится в списке, а каждый
 * провайдер S3 — отдельной строкой, потому что человек ищет «Wasabi»
 * или «Selectel», а не «S3-совместимое хранилище».
 */
object CloudCatalog {

    private val browserLogin = Localized("вход через браузер", "sign in via browser")
    private val loginPassword = Localized("логин и пароль", "login and password")
    private val accessKeys = Localized("ключи доступа", "access keys")

    private val login = Localized("Логин", "Login")
    private val loginOrEmail = Localized("Логин или почта", "Login or email")
    private val email = Localized("Почта", "Email")
    private val password = Localized("Пароль", "Password")
    private val server = Localized("Сервер", "Server")
    private val serverUrl = Localized("Адрес сервера", "Server address")
    private val serverUrlHint = Localized("Например, https://example.com/dav", "For example, https://example.com/dav")
    private val accessKeyId = Localized("Идентификатор ключа (Access Key ID)", "Access Key ID")
    private val secretKey = Localized("Секретный ключ (Secret Access Key)", "Secret Access Key")
    private val endpoint = Localized("Адрес хранилища (endpoint)", "Storage endpoint")
    private val region = Localized("Регион", "Region")

    /** Поля S3: ключи обязательны у всех, адрес и регион — смотря у кого. */
    private fun s3Fields(
        endpointHelp: Localized? = null,
        endpointRequired: Boolean = false,
        regionHelp: Localized? = null,
    ) = listOfNotNull(
        CatalogField("access_key_id", accessKeyId),
        CatalogField("secret_access_key", secretKey, isPassword = true),
        endpointHelp?.let { CatalogField("endpoint", endpoint, required = endpointRequired, help = it) },
        regionHelp?.let { CatalogField("region", region, required = false, help = it) },
    )

    val services: List<CatalogService> = listOf(
        // --- Вход через браузер -------------------------------------------------
        CatalogService(
            id = "yandex",
            title = Localized("Яндекс.Диск", "Yandex Disk"),
            subtitle = browserLogin,
            backend = "yandex",
            // force_confirm=yes заставляет Яндекс показать выбор аккаунта. Без
            // него второй диск молча привязывался к тому же аккаунту, что и
            // первый. Свои параметры rclone дописывает к адресу через «&».
            fixed = mapOf("auth_url" to "https://oauth.yandex.com/authorize?force_confirm=yes"),
            oauth = true,
            accent = 0xFFE53935,
            glyph = "Я",
            group = CatalogGroup.BROWSER,
            keywords = listOf("yandex", "россия", "russia"),
        ),
        CatalogService(
            id = "gdrive",
            title = Localized("Google Диск", "Google Drive"),
            subtitle = browserLogin,
            backend = "drive",
            // prompt=select_account — штатный параметр Google OAuth: без него вход
            // молча уходит в аккаунт, уже открытый в браузере. Адрес — тот же,
            // которым пользуется сам rclone, к нему только добавлен параметр.
            fixed = mapOf(
                "auth_url" to "https://accounts.google.com/o/oauth2/auth?prompt=select_account",
            ),
            // Свой идентификатор приложения — необязательный, но спрашивается
            // здесь, а не прячется: без него rclone ходит через общий на всех
            // идентификатор, который Google сильно ограничивает.
            fields = listOf(
                CatalogField(
                    key = "client_id",
                    label = Localized("Идентификатор приложения Google", "Google client ID"),
                    required = false,
                    help = Localized(
                        "Можно оставить пустым, но диск будет работать в десятки раз медленнее. " +
                            "Создав свой, не забудьте добавить себя в тестировщики — иначе Google " +
                            "ответит «403: access_denied».",
                        "Can be left empty, but the drive will be dozens of times slower. " +
                            "If you create your own, remember to add yourself as a test user — " +
                            "otherwise Google answers «403: access_denied».",
                    ),
                ),
                CatalogField(
                    key = "client_secret",
                    label = Localized("Секрет приложения Google", "Google client secret"),
                    required = false,
                    help = Localized("Выдаётся вместе с идентификатором.", "Issued together with the client ID."),
                ),
            ),
            hint = Localized(
                "Google Диску лучше дать свой идентификатор приложения. Без него rclone " +
                    "работает через общий, один на всех его пользователей: Google ограничивает " +
                    "его так, что список из 65 файлов занимал 33 секунды вместо одной. " +
                    "Пошаговая инструкция, включая обязательный шаг «добавить себя " +
                    "в тестировщики»: chistovik92.github.io/opendisk/USAGE.html",
                "Google Drive is better off with its own client ID. Without one rclone uses a " +
                    "shared ID common to all of its users; Google throttles it so heavily that " +
                    "listing 65 files took 33 seconds instead of one. Step-by-step instructions, " +
                    "including the mandatory «add yourself as a test user» step: " +
                    "chistovik92.github.io/opendisk/USAGE.html",
            ),
            oauth = true,
            accent = 0xFF43A047,
            glyph = "G",
            group = CatalogGroup.BROWSER,
            keywords = listOf("google", "gdrive", "drive"),
        ),
        CatalogService(
            id = "onedrive",
            title = Localized("OneDrive", "OneDrive"),
            subtitle = browserLogin,
            backend = "onedrive",
            fixed = mapOf(
                "auth_url" to
                    "https://login.microsoftonline.com/common/oauth2/v2.0/authorize?prompt=select_account",
            ),
            oauth = true,
            accent = 0xFF0288D1,
            glyph = "O",
            group = CatalogGroup.BROWSER,
            keywords = listOf("microsoft", "sharepoint", "office 365"),
        ),
        CatalogService(
            id = "dropbox",
            title = Localized("Dropbox", "Dropbox"),
            subtitle = browserLogin,
            backend = "dropbox",
            // Как у Яндекса: без force_reapprove повторное подключение уходит
            // в уже разрешённый аккаунт без вопросов.
            fixed = mapOf("auth_url" to "https://www.dropbox.com/oauth2/authorize?force_reapprove=true"),
            oauth = true,
            accent = 0xFF1565C0,
            glyph = "D",
            group = CatalogGroup.BROWSER,
        ),
        CatalogService(
            id = "gphotos",
            title = Localized("Google Фото", "Google Photos"),
            subtitle = browserLogin,
            backend = "gphotos",
            oauth = true,
            accent = 0xFFFBC02D,
            glyph = "Ф",
            group = CatalogGroup.BROWSER,
            keywords = listOf("google", "photos", "фотографии"),
        ),
        CatalogService(
            id = "box",
            title = Localized("Box", "Box"),
            subtitle = browserLogin,
            backend = "box",
            oauth = true,
            accent = 0xFF0061D5,
            glyph = "B",
            group = CatalogGroup.BROWSER,
        ),
        CatalogService(
            id = "pcloud",
            title = Localized("pCloud", "pCloud"),
            subtitle = browserLogin,
            backend = "pcloud",
            oauth = true,
            accent = 0xFF17BED0,
            glyph = "P",
            group = CatalogGroup.BROWSER,
            keywords = listOf("швейцария", "switzerland"),
        ),
        CatalogService(
            id = "zoho",
            title = Localized("Zoho WorkDrive", "Zoho WorkDrive"),
            subtitle = browserLogin,
            backend = "zoho",
            fields = listOf(
                CatalogField(
                    key = "region",
                    label = region,
                    required = false,
                    help = Localized(
                        "com — весь мир, eu — Европа, in — Индия, jp — Япония, com.cn — Китай, com.au — Австралия",
                        "com — global, eu — Europe, in — India, jp — Japan, com.cn — China, com.au — Australia",
                    ),
                ),
            ),
            oauth = true,
            accent = 0xFFD32F2F,
            glyph = "Z",
            group = CatalogGroup.BROWSER,
            keywords = listOf("индия", "india"),
        ),
        CatalogService(
            id = "hidrive",
            title = Localized("HiDrive", "HiDrive"),
            subtitle = browserLogin,
            backend = "hidrive",
            oauth = true,
            accent = 0xFFFF6F00,
            glyph = "H",
            group = CatalogGroup.BROWSER,
            keywords = listOf("strato", "германия", "germany"),
        ),
        CatalogService(
            id = "huaweidrive",
            title = Localized("Huawei Drive", "Huawei Drive"),
            subtitle = browserLogin,
            backend = "huaweidrive",
            oauth = true,
            accent = 0xFFC62828,
            glyph = "Hw",
            group = CatalogGroup.BROWSER,
            keywords = listOf("huawei cloud", "китай", "china"),
        ),
        CatalogService(
            id = "putio",
            title = Localized("Put.io", "Put.io"),
            subtitle = browserLogin,
            backend = "putio",
            oauth = true,
            accent = 0xFF546E7A,
            glyph = "Pi",
            group = CatalogGroup.BROWSER,
        ),
        CatalogService(
            id = "premiumizeme",
            title = Localized("premiumize.me", "premiumize.me"),
            subtitle = browserLogin,
            backend = "premiumizeme",
            oauth = true,
            accent = 0xFF6A1B9A,
            glyph = "pm",
            group = CatalogGroup.BROWSER,
        ),
        CatalogService(
            id = "sharefile",
            title = Localized("Citrix ShareFile", "Citrix ShareFile"),
            subtitle = browserLogin,
            backend = "sharefile",
            oauth = true,
            accent = 0xFF00897B,
            glyph = "SF",
            group = CatalogGroup.BROWSER,
            keywords = listOf("progress sharefile"),
        ),

        // --- Вход по паролю -----------------------------------------------------
        CatalogService(
            id = "mailru",
            title = Localized("Облако Mail.ru", "Mail.ru Cloud"),
            subtitle = loginPassword,
            backend = "mailru",
            fields = listOf(
                CatalogField("user", loginOrEmail),
                CatalogField(
                    key = "pass",
                    label = Localized("Пароль для внешних приложений", "Password for external applications"),
                    isPassword = true,
                    help = Localized(
                        "Не основной пароль от почты: его нужно создать в настройках безопасности Mail.ru",
                        "Not your main mail password: create one in Mail.ru security settings",
                    ),
                ),
            ),
            hint = Localized(
                "Mail.ru не принимает основной пароль от аккаунта — нужен отдельный пароль " +
                    "для внешних приложений.",
                "Mail.ru does not accept your main account password — a separate password for " +
                    "external applications is required.",
            ),
            accent = 0xFF1E88E5,
            glyph = "@",
            group = CatalogGroup.PASSWORD,
            keywords = listOf("mail", "vk", "россия", "russia"),
        ),
        CatalogService(
            id = "mega",
            title = Localized("MEGA", "MEGA"),
            subtitle = loginPassword,
            backend = "mega",
            fields = listOf(
                CatalogField("user", email),
                CatalogField("pass", password, isPassword = true),
            ),
            accent = 0xFFD9272E,
            glyph = "M",
            group = CatalogGroup.PASSWORD,
            keywords = listOf("новая зеландия", "new zealand"),
        ),
        CatalogService(
            id = "protondrive",
            title = Localized("Proton Drive", "Proton Drive"),
            subtitle = loginPassword,
            backend = "protondrive",
            fields = listOf(
                CatalogField("username", email),
                CatalogField("password", password, isPassword = true),
                CatalogField(
                    key = "2fa",
                    label = Localized("Код двухфакторной защиты", "Two-factor code"),
                    required = false,
                    help = Localized(
                        "Если она включена — код из приложения на момент добавления.",
                        "If it is enabled — the current code from your authenticator.",
                    ),
                ),
            ),
            accent = 0xFF6D4AFF,
            glyph = "Pr",
            group = CatalogGroup.PASSWORD,
            keywords = listOf("proton", "швейцария", "switzerland"),
        ),
        CatalogService(
            id = "koofr",
            title = Localized("Koofr", "Koofr"),
            subtitle = loginPassword,
            backend = "koofr",
            fixed = mapOf("provider" to "koofr"),
            fields = listOf(
                CatalogField("user", email),
                CatalogField(
                    key = "password",
                    label = Localized("Пароль приложения", "Application password"),
                    isPassword = true,
                    help = Localized(
                        "Создаётся в Koofr: «Настройки» → «Пароли».",
                        "Created in Koofr: «Preferences» → «Password».",
                    ),
                ),
            ),
            accent = 0xFF2E7D32,
            glyph = "K",
            group = CatalogGroup.PASSWORD,
            keywords = listOf("словения", "slovenia", "европа"),
        ),
        CatalogService(
            id = "digistorage",
            title = Localized("Digi Storage", "Digi Storage"),
            subtitle = loginPassword,
            backend = "koofr",
            fixed = mapOf("provider" to "digistorage"),
            fields = listOf(
                CatalogField("user", email),
                CatalogField("password", Localized("Пароль приложения", "Application password"), isPassword = true),
            ),
            accent = 0xFF1976D2,
            glyph = "Dg",
            group = CatalogGroup.PASSWORD,
            keywords = listOf("румыния", "romania", "rcs-rds"),
        ),
        CatalogService(
            id = "pikpak",
            title = Localized("PikPak", "PikPak"),
            subtitle = loginPassword,
            backend = "pikpak",
            fields = listOf(
                CatalogField("user", loginOrEmail),
                CatalogField("pass", password, isPassword = true),
            ),
            accent = 0xFF3F51B5,
            glyph = "Pk",
            group = CatalogGroup.PASSWORD,
        ),
        CatalogService(
            id = "internxt",
            title = Localized("Internxt Drive", "Internxt Drive"),
            subtitle = loginPassword,
            backend = "internxt",
            fields = listOf(
                CatalogField("email", email),
                CatalogField("pass", password, isPassword = true),
            ),
            accent = 0xFF0066FF,
            glyph = "In",
            group = CatalogGroup.PASSWORD,
            keywords = listOf("испания", "spain"),
        ),
        CatalogService(
            id = "filen",
            title = Localized("Filen", "Filen"),
            subtitle = loginPassword,
            backend = "filen",
            fields = listOf(
                CatalogField("email", email),
                CatalogField("password", password, isPassword = true),
                CatalogField(
                    key = "api_key",
                    label = Localized("Ключ API", "API key"),
                    isPassword = true,
                    help = Localized(
                        "Выдаётся в веб-версии Filen, в настройках аккаунта.",
                        "Issued in the Filen web app, in the account settings.",
                    ),
                ),
            ),
            accent = 0xFF212121,
            glyph = "Fl",
            group = CatalogGroup.PASSWORD,
            keywords = listOf("германия", "germany"),
        ),
        CatalogService(
            id = "opendrive",
            title = Localized("OpenDrive", "OpenDrive"),
            subtitle = loginPassword,
            backend = "opendrive",
            fields = listOf(
                CatalogField("username", loginOrEmail),
                CatalogField("password", password, isPassword = true),
            ),
            accent = 0xFF5D4037,
            glyph = "Od",
            group = CatalogGroup.PASSWORD,
        ),
        CatalogService(
            id = "ulozto",
            title = Localized("Uloz.to", "Uloz.to"),
            subtitle = loginPassword,
            backend = "ulozto",
            fields = listOf(
                CatalogField("username", login),
                CatalogField("password", password, isPassword = true),
            ),
            accent = 0xFF00A0DF,
            glyph = "U",
            group = CatalogGroup.PASSWORD,
            keywords = listOf("чехия", "czech"),
        ),
        CatalogService(
            id = "yandex-webdav",
            title = Localized("Яндекс.Диск по WebDAV", "Yandex Disk over WebDAV"),
            subtitle = Localized("если браузерный вход не подходит", "if browser sign-in does not suit you"),
            backend = "webdav",
            fixed = mapOf("url" to "https://webdav.yandex.ru", "vendor" to "other"),
            fields = listOf(
                CatalogField("user", login),
                CatalogField(
                    key = "pass",
                    label = Localized("Пароль приложения", "Application password"),
                    isPassword = true,
                    help = Localized(
                        "Создаётся на id.yandex.ru в разделе «Пароли приложений»",
                        "Created at id.yandex.ru under «App passwords»",
                    ),
                ),
            ),
            hint = Localized(
                "Для Яндекса по WebDAV нужен пароль приложения, а не пароль от аккаунта.",
                "Yandex over WebDAV needs an app password, not your account password.",
            ),
            accent = 0xFFD32F2F,
            glyph = "Я",
            group = CatalogGroup.PASSWORD,
            keywords = listOf("yandex", "webdav"),
        ),

        // --- Объектные хранилища ------------------------------------------------
        CatalogService(
            id = "s3-aws",
            title = Localized("Amazon S3", "Amazon S3"),
            subtitle = accessKeys,
            backend = "s3",
            fixed = mapOf("provider" to "AWS"),
            fields = s3Fields(
                regionHelp = Localized("Например, eu-central-1", "For example, eu-central-1"),
            ),
            accent = 0xFFFF9900,
            glyph = "S3",
            group = CatalogGroup.OBJECT_STORAGE,
            keywords = listOf("aws", "amazon"),
        ),
        CatalogService(
            id = "s3-cloudflare",
            title = Localized("Cloudflare R2", "Cloudflare R2"),
            subtitle = accessKeys,
            backend = "s3",
            fixed = mapOf("provider" to "Cloudflare"),
            fields = s3Fields(
                endpointRequired = true,
                endpointHelp = Localized(
                    "https://<идентификатор аккаунта>.r2.cloudflarestorage.com",
                    "https://<account id>.r2.cloudflarestorage.com",
                ),
            ),
            accent = 0xFFF38020,
            glyph = "R2",
            group = CatalogGroup.OBJECT_STORAGE,
        ),
        CatalogService(
            id = "b2",
            title = Localized("Backblaze B2", "Backblaze B2"),
            subtitle = accessKeys,
            backend = "b2",
            fields = listOf(
                CatalogField("account", Localized("Идентификатор ключа (keyID)", "Key ID (keyID)")),
                CatalogField("key", Localized("Ключ приложения (applicationKey)", "Application key"), isPassword = true),
            ),
            accent = 0xFFE21E29,
            glyph = "B2",
            group = CatalogGroup.OBJECT_STORAGE,
        ),
        CatalogService(
            id = "s3-wasabi",
            title = Localized("Wasabi", "Wasabi"),
            subtitle = accessKeys,
            backend = "s3",
            fixed = mapOf("provider" to "Wasabi"),
            fields = s3Fields(
                endpointHelp = Localized("Например, s3.eu-central-1.wasabisys.com", "For example, s3.eu-central-1.wasabisys.com"),
            ),
            accent = 0xFF00C853,
            glyph = "W",
            group = CatalogGroup.OBJECT_STORAGE,
        ),
        CatalogService(
            id = "storj",
            title = Localized("Storj", "Storj"),
            subtitle = Localized("ключ доступа", "access grant"),
            backend = "storj",
            fixed = mapOf("provider" to "existing"),
            fields = listOf(
                CatalogField(
                    key = "access_grant",
                    label = Localized("Ключ доступа (Access Grant)", "Access Grant"),
                    isPassword = true,
                ),
            ),
            accent = 0xFF0149FF,
            glyph = "Sj",
            group = CatalogGroup.OBJECT_STORAGE,
            keywords = listOf("tardigrade"),
        ),
        CatalogService(
            id = "s3-selectel",
            title = Localized("Selectel", "Selectel"),
            subtitle = accessKeys,
            backend = "s3",
            fixed = mapOf("provider" to "Selectel"),
            fields = s3Fields(
                endpointHelp = Localized("Например, s3.ru-1.storage.selcloud.ru", "For example, s3.ru-1.storage.selcloud.ru"),
            ),
            accent = 0xFFE53935,
            glyph = "Se",
            group = CatalogGroup.OBJECT_STORAGE,
            keywords = listOf("россия", "russia"),
        ),
        CatalogService(
            id = "s3-yandex",
            title = Localized("Yandex Object Storage", "Yandex Object Storage"),
            subtitle = accessKeys,
            backend = "s3",
            fixed = mapOf("provider" to "Other", "endpoint" to "https://storage.yandexcloud.net"),
            fields = s3Fields(),
            accent = 0xFFFFCC00,
            glyph = "YC",
            group = CatalogGroup.OBJECT_STORAGE,
            keywords = listOf("yandex cloud", "яндекс облако", "россия"),
        ),
        CatalogService(
            id = "s3-hetzner",
            title = Localized("Hetzner Object Storage", "Hetzner Object Storage"),
            subtitle = accessKeys,
            backend = "s3",
            fixed = mapOf("provider" to "Hetzner"),
            fields = s3Fields(
                endpointHelp = Localized("Например, fsn1.your-objectstorage.com", "For example, fsn1.your-objectstorage.com"),
            ),
            accent = 0xFFD50C2D,
            glyph = "Hz",
            group = CatalogGroup.OBJECT_STORAGE,
            keywords = listOf("германия", "germany"),
        ),

        // --- Свои серверы и протоколы -------------------------------------------
        CatalogService(
            id = "nextcloud",
            title = Localized("Nextcloud", "Nextcloud"),
            subtitle = Localized("свой сервер", "own server"),
            backend = "webdav",
            fixed = mapOf("vendor" to "nextcloud"),
            fields = listOf(
                CatalogField(
                    key = "url",
                    label = serverUrl,
                    help = Localized(
                        "https://сервер/remote.php/dav/files/ИМЯ",
                        "https://server/remote.php/dav/files/USERNAME",
                    ),
                ),
                CatalogField("user", login),
                CatalogField(
                    key = "pass",
                    label = Localized("Пароль приложения", "App password"),
                    isPassword = true,
                    help = Localized(
                        "«Настройки» → «Безопасность» → «Устройства и сеансы».",
                        "«Settings» → «Security» → «Devices & sessions».",
                    ),
                ),
            ),
            accent = 0xFF0082C9,
            glyph = "N",
            group = CatalogGroup.SERVERS,
        ),
        CatalogService(
            id = "owncloud",
            title = Localized("ownCloud", "ownCloud"),
            subtitle = Localized("свой сервер", "own server"),
            backend = "webdav",
            fixed = mapOf("vendor" to "owncloud"),
            fields = listOf(
                CatalogField("url", serverUrl, help = serverUrlHint),
                CatalogField("user", login),
                CatalogField("pass", password, isPassword = true),
            ),
            accent = 0xFF041E42,
            glyph = "oC",
            group = CatalogGroup.SERVERS,
        ),
        CatalogService(
            id = "seafile",
            title = Localized("Seafile", "Seafile"),
            subtitle = Localized("свой сервер", "own server"),
            backend = "seafile",
            fields = listOf(
                CatalogField("url", serverUrl, help = Localized("Например, https://cloud.example.com", "For example, https://cloud.example.com")),
                CatalogField("user", email),
                CatalogField("pass", password, isPassword = true),
            ),
            accent = 0xFFFF8F00,
            glyph = "Sf",
            group = CatalogGroup.SERVERS,
        ),
        CatalogService(
            id = "webdav",
            title = Localized("WebDAV", "WebDAV"),
            subtitle = Localized("любой сервер", "any server"),
            backend = "webdav",
            fields = listOf(
                CatalogField("url", serverUrl, help = serverUrlHint),
                CatalogField("user", login, required = false),
                CatalogField("pass", password, isPassword = true, required = false),
            ),
            accent = 0xFF6D4C41,
            glyph = "W",
            group = CatalogGroup.SERVERS,
        ),
        CatalogService(
            id = "sftp",
            title = Localized("SFTP", "SFTP"),
            subtitle = Localized("доступ по SSH", "access over SSH"),
            backend = "sftp",
            fields = listOf(
                CatalogField("host", server),
                CatalogField("user", login, required = false),
                CatalogField("pass", password, isPassword = true, required = false),
            ),
            accent = 0xFF455A64,
            glyph = "S",
            group = CatalogGroup.SERVERS,
            keywords = listOf("ssh"),
        ),
        CatalogService(
            id = "smb",
            title = Localized("Сетевая папка (SMB)", "Network share (SMB)"),
            subtitle = Localized("Windows, NAS", "Windows, NAS"),
            backend = "smb",
            fields = listOf(
                CatalogField("host", server),
                CatalogField("user", login, required = false),
                CatalogField("pass", password, isPassword = true, required = false),
            ),
            accent = 0xFF37474F,
            glyph = "\\\\",
            group = CatalogGroup.SERVERS,
            keywords = listOf("cifs", "samba", "nas", "synology", "qnap"),
        ),
        CatalogService(
            id = "ftp",
            title = Localized("FTP", "FTP"),
            subtitle = Localized("сервер FTP", "FTP server"),
            backend = "ftp",
            fields = listOf(
                CatalogField("host", server),
                CatalogField("user", login, required = false),
                CatalogField("pass", password, isPassword = true, required = false),
            ),
            accent = 0xFF00695C,
            glyph = "F",
            group = CatalogGroup.SERVERS,
        ),
    )

    /**
     * Бэкенды, которые облаками не являются: обёртки над другими облаками
     * (шифрование, кэш, объединение), локальный диск и память. В списке
     * «куда подключиться» они только мешают искать.
     */
    val NOT_CLOUDS = setOf(
        "alias", "archive", "cache", "chunker", "combine", "compress", "crypt",
        "doi", "hasher", "local", "memory", "tardigrade", "union",
    )

    /** Поля, которые показываем помимо обязательных. */
    val COMMON_OPTIONAL_FIELDS = setOf(
        "user", "pass", "username", "password", "email", "vendor", "host", "port", "url",
        "endpoint", "region", "access_key_id", "secret_access_key", "account", "key",
        "api_key", "client_id", "client_secret",
    )

    /** Поля формы для бэкенда (и его варианта — провайдера S3 и т. п.). */
    fun formOptions(
        provider: RcloneClient.Provider,
        variant: String? = null,
    ): List<RcloneClient.Option> = provider.options
        .filter { !it.advanced && (it.required || it.name in COMMON_OPTIONAL_FIELDS) }
        // Вариант уже выбран — спрашивать его снова незачем.
        .filter { !(variant != null && it.name == "provider") }
        .filter { it.appliesTo(variant) }
        .distinctBy { it.name }

    /**
     * Всё, что умеет встроенный rclone, в виде того же каталога.
     *
     * Строится из `config/providers`, поэтому не устаревает. У S3 каждый
     * провайдер — отдельная строка: человек ищет свой сервис по названию.
     * Отобранные вручную сервисы из [services] здесь не повторяются.
     */
    fun fromProviders(providers: List<RcloneClient.Provider>): List<CatalogService> {
        val curated = services.map { it.backend to it.fixed["provider"] }.toSet()
        return providers
            .filter { it.type !in NOT_CLOUDS }
            .flatMap { provider ->
                val variants = provider.options
                    .firstOrNull { it.name == "provider" && it.examples.isNotEmpty() }
                    ?.examples
                    ?.filter { it.value.isNotBlank() }
                if (variants.isNullOrEmpty()) {
                    listOf(generic(provider, null, provider.description))
                } else {
                    variants.map { generic(provider, it.value, it.help.lineSequence().first()) }
                }
            }
            .filter { (it.backend to it.fixed["provider"]) !in curated }
            .sortedBy { it.title.en.lowercase() }
    }

    private fun generic(
        provider: RcloneClient.Provider,
        variant: String?,
        description: String,
    ): CatalogService {
        val options = formOptions(provider, variant)
        val names = provider.options.map { it.name }
        // Признак браузерного входа — поле токена рядом с идентификатором
        // приложения и отсутствие обязательного пароля. У Mail.ru токен тоже
        // есть, но входят туда паролем.
        val oauth = "token" in names && "client_id" in names &&
            options.none { it.required && it.isPassword }
        return CatalogService(
            id = "rclone:${provider.type}" + (variant?.let { ":$it" } ?: ""),
            title = Localized(description.ifBlank { provider.name }, description.ifBlank { provider.name }),
            subtitle = Localized(
                listOfNotNull(provider.type, variant).joinToString(" · "),
                listOfNotNull(provider.type, variant).joinToString(" · "),
            ),
            backend = provider.type,
            fixed = variant?.let { mapOf("provider" to it) }.orEmpty(),
            fields = options
                .filter { !(oauth && it.name in setOf("client_id", "client_secret")) || it.required }
                .map { option ->
                    CatalogField(
                        key = option.name,
                        label = Localized(option.name, option.name),
                        isPassword = option.isPassword,
                        required = option.required,
                        help = option.shortHelp.takeIf { it.isNotBlank() }?.let { Localized(it, it) },
                    )
                },
            oauth = oauth,
            accent = 0xFF607D8B,
            glyph = provider.type.take(2).replaceFirstChar { it.uppercase() },
            group = CatalogGroup.ALL,
            keywords = listOfNotNull(provider.name, provider.type, variant),
        )
    }
}
