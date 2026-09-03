<div align="center">

<img src="docs/readme.png" width="100%" alt="Termorator banner" />

# Termorator

**Fork of [Termora](https://github.com/TermoraDev/termora) — cross-platform SSH terminal & SFTP, now as Termorator.**

[![License: AGPL-3.0](https://img.shields.io/badge/License-AGPL--3.0-green?style=flat-square)](https://opensource.org/license/agpl-v3)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.10-7F52FF?style=flat-square&logo=kotlin)](https://kotlinlang.org)
[![Platform](https://img.shields.io/badge/Platform-Windows%20%7C%20macOS%20%7C%20Linux-0B0B0B?style=flat-square)](#-download)
[![Fork](https://img.shields.io/badge/Fork-king--of--Jupiter%2Ftermora-181717?style=flat-square&logo=github)](https://github.com/king-of-Jupiter/termora)
[![Upstream](https://img.shields.io/badge/Upstream-TermoraDev%2Ftermora-24292f?style=flat-square&logo=github)](https://github.com/TermoraDev/termora)

[English](./README.md) · [Русский](./README.ru_RU.md)

</div>

> **Termorator** = Termora + “ator”. Тот же SSH-движок из оригинала (`upstream/2.x`), но с собственным брендингом, WinSCP-интеграцией, карточками хостов и пофикшенными мелочами (нет звука при `backspace`, восстановлен SFTP). Оригинал — **hstyi / TermoraDev** → https://github.com/TermoraDev/termora

---

## ✨ Что такое Termorator

* **Форк, а не клон.** SSH/SFTP ядро полностью взято из `TermoraDev/termora@2.x` (см. `plugin/internal/ssh`, `plugin/internal/sftppty`, `DatabaseManager.SFTP`). Сломанные ИИ-правки откатаны.
* **Брендинг:** `Application.getName() = Termorator`, `group = app.termorator`, баннер `BannerPanel`, `termora.title`, окно и трей — везде `Termorator`, в `О программе` — `Based on Termora by hstyi` + ссылка на форк.
* **Kotlin/JVM**, частично `XTerm`, цель — в т.ч. `Kotlin Multiplatform`.

```
 _______                                    _
|__   __|__ _ __ _ __ ___   ___  _ __ __ _| |_ ___  _ __
   | |/ _ \ '__| '_ ` _ \ / _ \| '__/ _` | __/ _ \| '__|
   | |  __/ |  | | | | | | (_) | | | (_| | || (_) | |
   |_|\___|_|  |_| |_| |_|\___/|_|  \__,_|\__\___/|_|
             Termorator — based on Termora
```

---

## 🚀 Почему Termorator

| Что добавили | Где |
|---|---|
| **WinSCP интеграция** — `Открыть через WinSCP` в дереве хостов, экспорт PuTTY `.ppk` (RSA/EC/Ed25519) | `WinSCP.kt`, `NewHostTree.kt` |
| **Карточки хостов + OS-иконки** — `Ubuntu/Debian/Fedora/Alma/Windows` детект `cat /etc/os-release` | `HostCardsPanel.kt`, `OSDetector.kt` |
| **Починка SSH** — откат `SSHTerminalTab` к оригиналу, возврат `SftpCommand`, `sftppty`, `DatabaseManager.SFTP` | `upstream/2.x` |
| **Нет писка** — `AuditoryCues.playList = null` + `beep` no-op для поля поиска/дерева | `ApplicationRunner`, `WelcomePanel` |
| **Старт без плясок с JDK** — `start.bat` авто-ставит `JAVA_HOME=C:\Program Files\JBR`, `settings.gradle.kts` `foojay 1.0.0` для `Gradle 9` | `start.bat`, `settings.gradle.kts` |

---

## ✨ Возможности (как в Termora)

- 🧬 Кроссплатформенно — Windows, macOS, Linux
- 🔐 Менеджер ключей (RSA/EC/Ed25519), `OpenSSH` → `PuTTY` для WinSCP
- 🖼️ X11 forwarding, 🧑‍💻 SSH-Agent, 💻 System info, 📊 Nvidia SMI
- 📁 GUI SFTP — `A↔B` напрямую, рекурсия, 6 параллельных задач, `rm -rf` оптимизация
- ⚡ Быстрые команды, сниппеты, поиск `Find Everywhere`

<div align="center">
  <img src="docs/readme.png" alt="Main" width="85%" />
</div>

### 🚀 Передача файлов
- Прямая передача сервер `A ↔ B`, рекурсивные папки, 6 потоков

<div align="center"><img src="docs/transfer.png" alt="Transfer" width="85%" /></div>

### 📝 Редактирование
- Авто-загрузка после сохранения, переименование, `chmod`, создание файлов

<div align="center"><img src="docs/transfer-edit.png" alt="Edit" width="85%" /></div>

### 💻 Хосты
- Иерархия как папки, теги, импорт из `FinalShell / Xshell / MobaXterm / Electerm` и др.

<div align="center"><img src="docs/host.png" alt="Hosts" width="85%" /></div>

### 🧩 Плагины (как в оригинале, часть выпилена в этом форке для фокуса на SSH)
- 🌍 Geo, 🔄 Sync (Gist/WebDAV), 🗂️ WebDAV, 📝 Editor, 📡 SMB, ☁️ S3/OBS/COS/OSS, 🔌 Serial, 🖥️ VNC
- 👉 Полный список оригинала → https://www.termora.app/plugins

---

## 📦 Скачать

**Termorator (форк):**
- 🧾 [Latest Release — king-of-Jupiter/termora](https://github.com/king-of-Jupiter/termora/releases/latest) ← качай здесь
- Оригинал для сравнения — [TermoraDev/termora/releases](https://github.com/TermoraDev/termora/releases/latest)

**Оригинальные каналы (указывают на upstream, не на форк):**
- 🍺 `brew install --cask termora` — upstream
- 🔨 `winget install TermoraDev.Termora` — upstream
- Microsoft Store — `Termora` (upstream)

> Для Termorator пока — ручная установка `zip`/`exe`/`dmg`/`AppImage` из Releases форка. `winget/brew` для `Termorator` не публикуется.

---

## 🛠️ Разработка

**Рекомендуется [JetBrains Runtime JDK 25](https://github.com/JetBrains/JetBrainsRuntime)** (оригинал требует `languageVersion = 25`).

```bat
# Windows — починенный старт (авто-JAVA_HOME, foojay 1.0.0)
start.bat

# или вручную
gradlew.bat run

# Linux/macOS
./gradlew run
```

* Сборка: `gradlew assemble` (требует `JBR 25`, ставится автоматом из `foojay`, но `start.bat` уже выставляет `C:\Program Files\JBR`)
* SSH ядро — из `upstream/2.x` (`TermoraDev/termora`), не трогай `plugin/internal/ssh` без сверки с оригиналом
* Баннер/имя — `BannerPanel.kt`, `Application.getName()`, `messages.properties: termora.title`, `SettingsOptionsPane.AboutOption`, `AppxManifest.xml`, `build.gradle.kts (group/vendor)`

---

## 🔧 Что именно восстановлено из оригинала

* `plugin/internal/ssh`: `SSHHostOptionsPane` (вернул `SFTP Option`), `SSHInternalPlugin` (вернул `SftpCommand`), `SSHTerminalTab` (без лагающей OS-детекции), `SftpCommandTerminalTabbedContextMenuExtension`
* `plugin/internal/sftppty/*` (4 файла)
* `database/DatabaseManager.SFTP`

Остались доп. фичи форка: `OSDetector.kt`, `WinSCP.kt`, карточки хостов — не ломают ядро.

---

## 📄 Лицензия и атрибуция

Двойная лицензия оригинала (`README: License`):

- **AGPL-3.0** — можно форкать, переименовывать (как `Termorator`), менять, **оставляя ссылку на оригинал** и исходники под AGPL-3.0. При распространении/хостинге — обязан дать исходники. `THIRDPARTY` сохранён.
- **Proprietary** — для закрытия кода — свяжись с автором.

**Атрибуция в форке:**
* `О программе` → `Termorator (based on Termora by hstyi)` + `TermoraDev/termora` + `king-of-Jupiter/termora (fork)` + `AGPL-3.0`
* Код — `Copyright hstyi / TermoraDev`, модификации — форк. Трейдмарк `Termora` не выдаётся за свой.

> Вопросы — Issues в этом репозитории: https://github.com/king-of-Jupiter/termora/issues (upstream — https://github.com/TermoraDev/termora/issues)
