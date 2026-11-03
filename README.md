# Winger VPN — Android

Клиент под кабинет my.wingerapp.pro и подписку Remnawave.

## Что работает
- Вход: логин и пароль кабинета (`POST /api/v1/auth/login`, пароль как SHA-256)
- После входа берётся подписка (`GET /api/v1/subscription`) и её `url` из Remnawave
- Подписка качается с заголовком `x-hwid`, серверы показываются под орлом
- Дизайн: орёл, «Нажми на орла», тариф и срок, 4 устройства, ручной выбор сервера

## Чего ещё нет
Шифрование трафика. Системный туннель (`WingerVpnService`) есть, но ядро VLESS/Reality (sing-box) не вшито. Пока кнопка только отмечает сервер. Если поднять туннель без ядра, телефон потеряет интернет.

## Сборка
Android Studio → Open `winger-android` → Run.
Или GitHub Actions (`.github/workflows/apk.yml`) после добавления Gradle Wrapper.
