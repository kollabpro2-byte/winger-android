# Winger VPN — Android

Клиент под кабинет `my.wingerapp.pro` и подписку Remnawave. Один Compose-модуль,
ядро — sing-box через `libbox` (форк `sing-box-lx` с XHTTP).

## Что работает

- Вход и регистрация прямо в приложении (`POST /api/v1/auth/login|register`,
  пароль передаётся как SHA-256, регистрация помечается `source=app` + HWID).
- Подписка из кабинета, конфиг качается с заголовком `x-hwid` (формат Happ),
  список серверов показывается с TCP-пингом.
- Реальный туннель: sing-box запускается как `VpnService`, TUN поднимается на
  gvisor-стеке, VLESS + Reality/TLS, транспорты `tcp`, `ws`, `grpc`,
  `httpupgrade`, `h2/http` и `xhttp`/`splithttp` (включая uplink/padding/
  seq/session-настройки XHTTP).
- DNS уходит **внутри** туннеля, IPv6 не течёт мимо (маршрут `::/0` в tun).
- «Без туннеля»: домены из списка идут напрямую, остальное — в прокси.
- Mux и MTU 1400 — применяются к ядру, а не только к настройкам в UI.
- Аккаунт: тариф, срок, трафик, устройства (с удалением), оплата СБП, поддержка
  с тикетами и вложениями, привязка Telegram.
- Если подписка не содержит VLESS-сервера, туннель **не поднимается** — вместо
  «тихого» прямого соединения показывается ошибка (fail closed).
- Уведомление с кнопкой «Отключить», `START_STICKY` переиспользует последний
  конфиг, состояние туннеля идёт из ядра (`TunnelState`), а не ставится «на глаз».

## Приватность и подпись

- Логин, пароль, cookie сессии и последний конфиг лежат в
  `EncryptedSharedPreferences` (ключ в Android Keystore); старый plaintext-файл
  мигрируется и стирается.
- `android:allowBackup="false"` + правила извлечения данных: prefs не уходят в
  облако и при переносе на новое устройство.
- Релиз подписывается **не** debug-ключом: `debuggable=false`, R8 + shrink,
  ключ живёт только в секретах CI/`keystore.properties` (см. `.gitignore`).
- Лог ядра включается только в debug-сборке (`BuildConfig.CORE_LOG`).

## Сборка

```bash
# ядро не лежит в репозитории
mkdir -p app/libs && curl -L -o app/libs/libbox.aar \
  https://github.com/Leadaxe/sing-box-lx/releases/download/v1.14.4-lx.33/libbox-1.14.4-lx.33.aar

./gradlew testDebugUnitTest     # конвертер конфигов + разбор подписки
./gradlew assembleDebug         # локальная сборка
./gradlew assembleRelease       # нужен keystore.properties (см. пример ниже)
```

`keystore.properties` (в git не попадает, есть шаблон `keystore.properties.example`):

```properties
storeFile=/absolute/path/to/winger-release.jks
storePassword=…
keyAlias=winger
keyPassword=…
```

Для CI те же значения задаются секретами: `ANDROID_KEYSTORE_BASE64`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.
`.github/workflows/apk.yml` собирает release, проверяет sha256 `libbox.aar`,
прогоняет unit-тесты, проверяет, что APK подписан и не `debuggable`, и создаёт
GitHub Release только если тега `v<versionName>` ещё нет.

## Структура

```
pro/wingerapp/vpn/
  MainActivity.kt      точка входа: разрешение VPN, запуск/остановка сервиса
  AppViewModel.kt      сессия, подписка, выбранный сервер, пинги (StateFlow)
  WingerVpnService.kt  VpnService + PlatformInterface для libbox
  SingBox.kt           Xray-JSON из подписки → конфиг sing-box (DNS, правила, MTU)
  Config.kt            разбор подписки Happ/Remnawave (JSON и base64)
  Store.kt             настройки в EncryptedSharedPreferences
  Diag.kt, VpnLog.kt   диагностика ядра и сбор крашей
  ui/
    LoginScreen.kt     вход и регистрация
    HomeScreen.kt      вкладки, таймер, трафик, навигация
    HomeTab.kt         кнопка подключения и список серверов
    AccountTab.kt      тариф, оплата, устройства, поддержка
    SettingsTab.kt     DNS, Mux, MTU, «без туннеля», лог
    Components.kt      общие элементы (поле, секция, орел, флаги)
    Format.kt          группировка серверов, трафик, дни (чистые функции)
    Strings.kt         доступ к ресурсам из любого scope, plurals, числа
    Theme.kt           палитра
```

Экраны держат только UI-состояние (вкладка, диалоги, секундомер), данные и
сетевые вызовы — в `AppViewModel`, поэтому переворот экрана больше не выбрасывает
загруженную подписку.

## Локализация

Все пользовательские строки — в `res/values/strings.xml` (русский, по умолчанию) и
`res/values-en/strings.xml`. Склонения — через `<plurals name="days">`, числа — с
учётом локали (`1,2` в ru, `1.2` в en) через `Strings.number()`.

`stringResource` вызывается только из композабл-скоупа, а часть строк собирается в
обработчиках клика и корутинах, поэтому экраны читают ресурсы через
`rememberStrings()` — он работает в любом скоупе.

## Пиннинг сертификата

`res/xml/network_security_config.xml` закрепляет SPKI-хеши цепочки
`my.wingerapp.pro` (лист, промежуточный Let's Encrypt "YE2" и ISRG Root X2) с
`expiration`, чтобы при смене сертификата приложение не «залочилось» навсегда.
Хост подписки намеренно не закреплён: он приходит из панели и может жить на другом
домене со своим сертификатом.

Порядок ротации: добавить новый пин в конфиг и выпустить релиз **до** смены
сертификата на сервере.

## Ограничения

- Поддерживается VLESS; hysteria/trojan/ss в подписке приведут к ошибке, а не к
  тихому direct-туннелю.
- Работает только на `arm64-v8a` и `armeabi-v7a`.
- `TunOptions` из колбэка ядра не читается: на `libbox 1.14.4-lx.33` с
  `auto_route=false` он приводит к нативной панике, поэтому адрес/MTU/DNS
  ставятся через `VpnService.Builder`.
- Пароль хешируется SHA-256 без соли — это контракт панели; переход на
  нормальную схему требует изменений на сервере.
