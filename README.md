# Lite Launcher

Лёгкий главный экран для Android TV: только ТВ-приложения, свой фон, без рекомендаций, рекламы и анимированных обоев. Интерфейс собран без сторонних библиотек.

## Установка

Скачайте `lite-launcher.apk` из последнего релиза и выполните:

```
adb install -r lite-launcher.apk
adb shell pm grant ru.dimanonorm.tvlauncher android.permission.READ_MEDIA_IMAGES
adb shell cmd package set-home-activity ru.dimanonorm.tvlauncher/.MainActivity
```

## Управление

- **ОК** — открыть приложение.
- **Долгое ОК** или кнопка **Меню** на плитке — сдвинуть, скрыть, сведения, удалить.
- Кнопка **Меню** вверху — выбор обоев, скрытые приложения, сброс порядка.
- **Настройки** — системные настройки телевизора.

## Обои

Положите картинку (jpg, png, webp) в папку `Wallpapers` во внутренней памяти:

```
adb shell mkdir -p /sdcard/Wallpapers
adb push wallpaper.png /sdcard/Wallpapers/
```

Затем «Меню → Выбрать обои».
