# Scan3D — 3D-сканер на ARCore (Kotlin + Compose)

## Сборка APK
1. Откройте папку в Android Studio (Ladybug 2024.2+ / JDK 17). Дождитесь Gradle Sync.
   (Если студия просит Gradle — выберите 8.9.)
2. Подключите телефон по USB (отладка включена) → Run ▶, либо
   Build → Build Bundle(s)/APK(s) → Build APK(s).
   Файл: app/build/outputs/apk/debug/app-debug.apk

## Требования к телефону
Поддержка ARCore + Depth API: https://developers.google.com/ar/devices
Сервисы Google Play для AR установятся автоматически при первом запуске.

## Как пользоваться
Скан → Старт → медленно обходите объект (30–60 см до него, хорошее освещение) → Сохранить.
Просмотр: 1 палец — вращение, щипок — зум. Экспорт: PLY / OBJ через меню «Поделиться».

## Сборка APK без Android Studio (GitHub)
1. Создайте репозиторий на github.com и загрузите в него содержимое папки Scan3D (включая .github).
2. Вкладка Actions → дождитесь зелёной галочки "Build APK" (3–6 минут).
3. Откройте запуск → Artifacts → Scan3D-apk → скачайте, распакуйте, установите app-debug.apk на телефон.
