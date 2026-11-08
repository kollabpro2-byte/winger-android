# Winger Android — что изменилось

Ветка `fix/security-and-tunnel`, версия **0.1.75** (было 0.1.72).
Ветка собрана, тесты проходят, release-APK подписывается и не является `debuggable`.

| | 0.1.72 (было) | 0.1.75 (стало) |
|---|---|---|
| Тип сборки | `assembleDebug`, `application-debuggable` | `release`: `debuggable=false`, R8 + shrink |
| Ключ подписи | `app/debug.keystore` в репозитории, пароль `android` | только `keystore.properties` / секреты CI |
| Секреты | логин, пароль, cookie в plaintext prefs | `EncryptedSharedPreferences`, `allowBackup=false` |
| Размер APK | 169 МБ (universal) | 81 МБ (arm64) / 73 МБ (armv7) |
| `classes.dex` | 18.2 МБ | 2.7 МБ |
| Мусор в git | `libv2ray.aar` 60 МБ, `geoip.dat` 16.5 МБ, `geosite.dat` 11.4 МБ | удалено |
| Тесты | нет | 19 unit-тестов |
| Языки | только ru, строки в коде | ru + en, 103 ключа × 2 |

Коммиты ветки:

```
8e5254e refactor(ui): экраны, ViewModel, i18n, тесты, пиннинг сертификата
a40c25c fix(tunnel): резолвер только когда прокси задан именем
62f4ec6 fix(tunnel): резолв прокси, отсев неизвестных XHTTP-ключей
7c51b96 ci: неподписанная сборка на ветках, публикация только с main
a3eb8b8 fix(security,tunnel): подпись, шифрованные prefs, рабочие настройки
```

---

## 1. Почему не работал VPN («значок есть, интернета нет»)

Две независимые причины, обе подтверждены логами и содержимым `libbox.so`:

**1.1. Ядро отвергало конфиг XHTTP.**
Ответ ядра: `outbounds[1].transport.session_id_key: json: unknown field`.
Проверка строк JSON-тегов внутри `libbox.so` (1.14.4-lx.33): `session_id_key` —
**0 вхождений**, при этом `path`, `host`, `mode`, `x_padding_bytes`,
`x_padding_obfs_mode`, `seq_key`, `session_key`, `session_placement` — есть.
Одно лишнее поле обрывает разбор всего конфига, поэтому XHTTP-ноды не стартовали.

**1.2. Ядро не могло отрезолвить адрес прокси.**
Вторая нода (`plad.wingertop.pro:443`, Reality/TCP) стартовала успешно, но
трафика не было: в конфиге не было резолвера вне туннеля. В бинаре есть и текст
ошибки `missing route.default_domain_resolver or domain_resolver in dial fields`,
и само поле `default_domain_resolver`.

Что сделано:

- XHTTP отправляет только существующие в этой сборке ключи; `session_id_*`,
  `uplink_data_*`, `sc_*`, `xmux` убраны как Xray-only.
- Второй проход «минимальный транспорт» при любой ошибке декодирования
  (`unknown field`, `decode config`, `cannot unmarshal`), а не только при
  `unknown field`.
- Адрес прокси резолвится в приложении (IPv4 первым, таймаут 3 с), домен остаётся
  `server_name` для TLS/Reality — SNI не ломается.
- `route.default_domain_resolver: "bootstrap"` добавляется **только** если адрес
  так и остался именем: конфиг с IP не получает лишних полей.
- Ответы DNS из `0.0.0.0/8`, loopback, `169.254/16`, `198.18/15`, `224/3`
  (включая `240/4`), `::`, `::1`, `fe80::/10` не используются как адрес прокси —
  иначе туннель звонит в никуда.

## 2. Безопасность дистрибуции

- Release-сборка: `isDebuggable = false`, R8, `shrinkResources`, ключ только из
  `keystore.properties` или env `WINGER_*`.
- `app/debug.keystore` удалён из репозитория и из истории ветки.
- Логин, пароль, cookie и последний конфиг — в `EncryptedSharedPreferences`
  (ключ в Android Keystore), старый plaintext-файл мигрируется и стирается.
- `allowBackup="false"` + `data_extraction_rules.xml`.
- Лог ядра включён только в debug (`BuildConfig.CORE_LOG`); в release его можно
  включить тумблером в настройках (переключатель раньше был мёртвым).
- CI проверяет, что APK подписан и не `debuggable`, и **не публикует** релиз без
  ключа: `release build is unsigned, refusing to publish`.
- `libbox.aar` тянется с закреплённым sha256
  `b1f569b7cf1744b810b2d0c7a05bbe63aa156092cf84811e21dc4e4bb90e0cc8` —
  подмена ассета ломает сборку, а не уезжает пользователям.

## 3. Честность настроек и туннель

Раньше половина тумблеров не доходила до ядра: Xray-JSON конвертировался в
sing-box, но переносился только один VLESS-outbound.

| Настройка | Было | Стало |
|---|---|---|
| «Без туннеля» | отбрасывалась | `domain_suffix → direct` в `route.rules` |
| Mux | отбрасывался | `multiplex` (кроме vision) |
| MTU 1400 | только в UI | и в `VpnService.Builder`, и в `tun.mtu` |
| DNS | шёл мимо туннеля | `dns.servers[].detour = "proxy"` + `bootstrap` вне туннеля |
| FakeDNS / фрагментация / гео-базы | отбрасывались | убраны из настроек, а не имитировались |

Дополнительно:

- **Fail closed**: подписка без VLESS → ошибка, а не «тихий» direct-туннель.
- **IPv6**: `addRoute("::", 0)` без IPv6-адреса → v6 в чёрную дыру, а не мимо VPN.
- **fd**: `pfd.detachFd()`, ядро владеет дескриптором (убрано двойное закрытие).
- **Транспорты**: добавлены `ws`, `grpc`, `httpupgrade`, `h2/http`; `flow` vision
  остаётся только для TCP/raw.
- **Состояние туннеля** идёт из ядра (`TunnelState`), а не ставится «на глаз»;
  отказ в разрешении VPN и падение ядра видны как ошибка.
- Уведомление с кнопкой «Отключить»; `START_STICKY` переиспользует последний
  конфиг; после провала старта сервис останавливается (нет цикла рестартов).
- **401** от панели возвращает на экран входа вместо «Нужно приобрести подписку».
- `setMetered` по фактической сети; `hwid` без общего фолбэка-константы.

## 4. Архитектура и локализация

`MainActivity.kt` 1104 строки → `MainActivity` (95) + `AppViewModel` (175) +
`ui/` (LoginScreen, HomeScreen, HomeTab, AccountTab, SettingsTab, Components,
Format, Strings, Theme).

- Данные и сеть (сессия, подписка, выбранный сервер, пинги) — в `AppViewModel`
  (`StateFlow`); переворот экрана больше не выбрасывает загруженную подписку.
- Эфемерное состояние (вкладка, диалоги, секундомер) осталось в композаблах.
- Строки: `res/values/strings.xml` (ru) + `res/values-en/strings.xml`, 103 ключа
  в каждой, покрытие 103/103. Склонения — `<plurals name="days">`, числа с учётом
  локали (`1,2` в ru, `1.2` в en).
- `stringResource` не работает в обработчиках клика и корутинах, поэтому экраны
  читают ресурсы через `rememberStrings()`.

## 5. Пиннинг сертификата

`res/xml/network_security_config.xml`:

| Что | SPKI SHA-256 |
|---|---|
| лист `wingerapp.pro` | `sYYmfHBnAneTGcHZWypM9HR6t2z6fnnCr2JxLnVoCCA=` |
| промежуточный Let's Encrypt «YE2» | `s/tdAOmUzd8syaTuqfgGvFcn6DzA5Cmb+Vby1ST+U3Y=` |
| ISRG Root X2 | `sCkq5UWXjg+7mKu9lMhhYF5bGLsy7VI/UNW3tccdR7w=` |

- `expiration="2027-11-01"` — после этой даты пиннинг отключается, чтобы смена
  сертификата не заблокировала уже установленные приложения навсегда.
- Хост подписки намеренно не закреплён: он приходит из панели и может жить на
  другом домене со своим сертификатом — неверный пин оставил бы пользователя без
  серверов.
- Ротация: добавить новый пин и выпустить релиз **до** смены сертификата.

## 6. Тесты: 19/19

Конвертер конфига, подписка (JSON и base64), записи «без туннеля», группировка
серверов, подсчёт дней, отсутствие секретов в логе, резолвер домена, отсев
подменённых DNS-ответов, fail closed, mux vs vision, транспорты.

Два бага нашли сами тесты:

1. Сервер с именем «YouTube обход» попадал сразу в две группы — теперь каждый
   сервер ровно в одной секции.
2. `readHapp` использовал `android.util.Base64` — base64-ветка была непокрываема
   вне устройства; переведено на `java.util.Base64.getMimeDecoder()` (терпит
   переводы строк в подписках).

## 7. Что и как проверено

Сборка: Gradle 8.7 + JDK 21 (JBR из Android Studio) + Android SDK 35.

```
gradle testDebugUnitTest   → 19 тестов, 0 падений
gradle assembleDebug       → BUILD SUCCESSFUL
gradle assembleRelease     → BUILD SUCCESSFUL (R8 + lintVital + shrink)
```

Артефакт (проверено `aapt2` / `apksigner` на собранном APK):

```
package: pro.wingerapp.vpn  versionCode=75  versionName=0.1.75  minSdk=26  targetSdk=35
application-debuggable: отсутствует
native-code: arm64-v8a
arm64 APK: 85 212 453 Б (81 МБ)   classes.dex: 2 695 340 Б (было 18.2 МБ)
armv7 APK: 76 800 977 Б (73 МБ)
ресурс network_security_config: есть, 3 pin + expiration
локали: () "Вход в аккаунт" / (en) "Sign in"
```

Тестовый APK для проверки на телефоне (подписан временным ключом `CN=Winger Test`,
ставится поверх предыдущей тестовой сборки без удаления):

```
~/Downloads/winger-0.1.75-arm64-test.apk
```

## 8. Что нужно сделать владельцу

1. **Release-ключ** и секреты CI:
   ```bash
   keytool -genkeypair -v -keystore winger-release.jks -alias winger \
     -keyalg RSA -keysize 4096 -validity 10000
   base64 -w0 winger-release.jks   # → ANDROID_KEYSTORE_BASE64
   ```
   Плюс `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.
2. **Смена подписи**: старый APK 0.1.72 подписан публичным debug-ключом, поэтому
   после мержа пользователям нужна переустановка (удалить → поставить). Релиз
   `v0.1.72` стоит удалить или пометить как скомпрометированный.
3. **Smoke-тест на устройстве** (не проверяется на компьютере): подключение
   Reality + XHTTP, `dnsleaktest.com`, `test-ipv6.com`, «Без туннеля» (банк
   открывается напрямую), Mux / MTU, отказ в разрешении VPN, кнопка «Отключить»
   в шторке, переключение языка системы.

## 9. Что осталось (по приоритету)

1. **Supply chain**: собирать libbox самому из закреплённого коммита с
   `-ldflags="-s -w" -trimpath` и strip — APK упадёт примерно до 40 МБ и закроется
   риск подмены ядра в чужом форке.
2. **Анти-абьюз триалов**: Play Integrity на `register` + серверный подписанный
   device-id вместо доверия к `hwid` из клиента.
3. **Пароль**: `sha256(password)` — контракт панели; нужен Argon2id на сервере.
4. **Оплата**: `source: "web"` в `/payments/checkout` не совпадает с `source=app`
   в регистрации — сломает source-aware возврат после оплаты.
5. **UI**: adaptive icon вместо PNG 91 КБ, раздельный туннель (per-app),
   always-on VPN, авто-фейловер на живую ноду, счётчики трафика (в этой сборке
   libbox нет stats-API, а `/proc/net/dev` приложениям недоступен с Android 10).

## 10. Файлы

**Добавлено**: `AppViewModel.kt`, `ui/` (9 файлов), `res/values/strings.xml`,
`res/values-en/strings.xml`, `res/xml/network_security_config.xml`,
`app/src/test/...TunnelConfigTest.kt`, `keystore.properties.example`, `.gitignore`,
`.github/workflows/apk.yml`, `CHANGELOG.md`.

**Удалено**: `app/debug.keystore`, `app/libs/libv2ray.aar`,
`assets/geoip.dat`, `assets/geosite.dat`, `scripts/rewrite_libbox.py`, мёртвый
Xray-слой (geo/mux/fragment/fakeDNS/DoH/shift-порты), мёртвый UI-код (WebView-
кабинет, CustomTabs, `claimTelegramGift`).
