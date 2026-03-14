# Parser Book 2 — Android App

WebView приложение для парсинга книг с форума 4PDA и публикации серий.

## Функциональность

### 3 вкладки (BottomNavigation):

| Вкладка | Функция |
|---------|---------|
| **Форум** | WebView с `4pda.to/forum/index.php?showforum=18` — просматривать темы, авторизоваться |
| **Парсер** | Локальный парсер книг (FB2, EPUB, PDF, ZIP). Генерация BB-кода |
| **Публикация** | WebView с `4pda.to/forum/index.php?act=zfw&f=18` — форма публикации |

### Парсер книг (assets/parser.html)

- ✅ FB2 (UTF-8 и Windows-1251)
- ✅ FB2.ZIP и вложенные архивы (рекурсивно)
- ✅ EPUB (с Calibre метаданными для серий)
- ✅ PDF (метаданные + рендер обложки)
- ✅ Множественные авторы
- ✅ Генерация BB-кода для 4PDA
- ✅ Пакетное скачивание FB2.ZIP
- ✅ Транслитерация имён файлов
- ✅ Управление порядком книг (↑↓)
- ✅ Обложки с оптимизацией (resize 500px)

### JavaScript ↔ Android Bridge

**Из парсера в Android:**
```javascript
AndroidBridge.sendBBCodeToForum(bbCode);  // → открывает вкладку Публикация и вставляет BB-код
AndroidBridge.copyToClipboard(text);       // → копирует в системный буфер
AndroidBridge.showToast(message);          // → показывает Toast
AndroidBridge.getPlatform();               // → "android"
```

**BB-код инжектируется** автоматически в textarea форумной формы публикации.

---

## Сборка APK

### Требования
- Android Studio Hedgehog (2023.1.1) или новее
- JDK 17
- Android SDK 34

### Шаги

```bash
# 1. Открыть в Android Studio
File → Open → выбрать папку parser-book-2

# 2. Синхронизировать Gradle
File → Sync Project with Gradle Files

# 3. Собрать Debug APK
Build → Build Bundle(s)/APK(s) → Build APK(s)

# 4. APK будет в:
# app/build/outputs/apk/debug/app-debug.apk
```

### Или через командную строку

```bash
cd parser-book-2
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

---

## Структура проекта

```
parser-book-2/
├── app/
│   ├── src/main/
│   │   ├── java/com/bookparser/app/
│   │   │   └── MainActivity.java       ← главный файл
│   │   ├── assets/
│   │   │   └── parser.html             ← парсер книг (HTML+JS)
│   │   ├── res/
│   │   │   ├── layout/activity_main.xml
│   │   │   ├── menu/bottom_nav_menu.xml
│   │   │   ├── values/{colors,strings,themes}.xml
│   │   │   ├── drawable/{ic_forum,ic_book,ic_publish}.xml
│   │   │   └── xml/{network_security_config,file_paths}.xml
│   │   └── AndroidManifest.xml
│   ├── build.gradle
│   └── proguard-rules.pro
├── build.gradle
├── settings.gradle
└── gradle/wrapper/gradle-wrapper.properties
```

---

## Доработки при необходимости

### Добавить ic_launcher иконки

В Android Studio: правой кнопкой на `res` → New → Image Asset → выбрать иконку

### Изменить URL форума

В `MainActivity.java`:
```java
private static final String URL_FORUM_BROWSE  = "https://4pda.to/forum/index.php?showforum=18";
private static final String URL_FORUM_PUBLISH = "https://4pda.to/forum/index.php?act=zfw&f=18";
```

### Изменить парсер

Редактировать `app/src/main/assets/parser.html` — обычный HTML/JS файл.

### Добавить тёмную тему

В `values/themes.xml` изменить родительскую тему на:
```xml
parent="Theme.MaterialComponents.DayNight.NoActionBar"
```

И добавить `values-night/themes.xml` с тёмными цветами.

---

## Требования к устройству

- Android 8.0 (API 26) и выше
- Интернет для загрузки 4PDA и CDN библиотек
- Разрешения: INTERNET, READ_EXTERNAL_STORAGE (для файлов)

---

## Примечания

- Авторизация на 4PDA сохраняется через CookieManager (общие куки между форум-вкладкой и публикацией)
- Парсер работает локально, файлы не покидают устройство
- BB-код автоматически вставляется в форму публикации при переходе на вкладку «Публикация»
