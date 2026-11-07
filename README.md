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

## Ограничения

- Поддерживается VLESS; hysteria/trojan/ss в подписке приведут к ошибке, а не к
  тихому direct-туннелю.
- Работает только на `arm64-v8a` и `armeabi-v7a`.
- `TunOptions` из колбэка ядра не читается: на `libbox 1.14.4-lx.33` с
  `auto_route=false` он приводит к нативной панике, поэтому адрес/MTU/DNS
  ставятся через `VpnService.Builder`.
- Пароль хешируется SHA-256 без соли — это контракт панели; переход на
  нормальную схему требует изменений на сервере.
