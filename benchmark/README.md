# Запись Baseline Profile

Профиль лежит в `app/src/main/baseline-prof.txt` (13 185 строк HRF). Он записан
на эмуляторе `Pixel_10_Pro` и **вшит в APK** — при установке Android заранее
компилирует перечисленные методы, поэтому старт и первые экраны быстрее
на **любом** устройстве (профиль описывает наш код, а не железо).

## Как обновить профиль

1. Запустить эмулятор или подключить телефон:
   ```
   ~/Library/Android/sdk/emulator/emulator -avd Pixel_10_Pro -no-window -no-audio \
       -no-boot-anim -no-snapshot -gpu swiftshader_indirect &
   adb wait-for-device
   ```
2. Прогнать запись:
   ```
   JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
   ~/.local/gradle-8.7/bin/gradle :app:generateBaselineProfile
   ```
3. Если задача падает на шаге извлечения профиля (см. ниже) — забрать профиль
   вручную; он уже лежит на устройстве:
   ```
   adb root
   adb pull /data/misc/profman/pro.wingerapp.vpn-primary.prof.txt \
            app/src/main/baseline-prof.txt
   ```
4. Пересобрать APK (`assembleRelease`) и проверить, что профиль внутри:
   ```
   unzip -l app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk | grep dexopt
   ```

## Известные проблемы

- `benchmark-macro-junit4` 1.3.3 падает с
  `Expected pm dump-profiles stdout to be either blank or "Profile saved to ..."`,
  потому что новые версии Android печатают дополнительные строки. Библиотека
  обновлена до 1.4.1, но и она на эмуляторе с программным рендером может
  зависать на извлечении профиля — тогда используем шаг 3 (ручное копирование).
- `startActivityAndWait()` в тесте заменён на `am start` через shell: APK тяжёлый
  (80 МБ нативной библиотеки), и macrobenchmark не дожидается подтверждения
  первого кадра на эмуляторе.

## Ожидаемый эффект

Профиль ускоряет то, что реально исполняется при холодном старте. После R8 часть
правил отбрасывается (методы инлайнятся или удаляются), поэтому итоговый
бинарь профиля небольшой (~4 КБ против ~3.3 КБ у профилей библиотек).
Практический выигрыш — на слабых устройствах заметнее, чем на флагманах.
