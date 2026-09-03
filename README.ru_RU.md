<div align="center">

<img src="docs/readme.png" width="100%" alt="Termorator" />

# Termorator

**Форк [Termora](https://github.com/TermoraDev/termora) — кроссплатформенный SSH-терминал и SFTP.**

[![Лицензия: AGPL-3.0](https://img.shields.io/badge/Лицензия-AGPL--3.0-green?style=flat-square)](https://opensource.org/license/agpl-v3)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.10-7F52FF?style=flat-square&logo=kotlin)](https://kotlinlang.org)
[![Форк](https://img.shields.io/badge/Форк-king--of--Jupiter%2Ftermora-181717?style=flat-square&logo=github)](https://github.com/king-of-Jupiter/termora)

[English](./README.md) · [Русский](./README.ru_RU.md)

</div>

> **Termorator** — тот же движок `TermoraDev/termora@2.x`, но под своим именем: WinSCP-интеграция, карточки хостов с иконками ОС, починка SSH и мелочи (нет писка при `backspace`, восстановлен SFTP). Оригинал — **hstyi**.

---

## 🚀 Почему Termorator

| Фича форка | Где |
|---|---|
| **WinSCP** — «Открыть через WinSCP», экспорт `.ppk` (RSA/EC/Ed25519) | `WinSCP.kt` |
| **Карточки + иконки ОС** — детект `cat /etc/os-release` | `HostCardsPanel`, `OSDetector` |
| **Починка SSH** — `SSHTerminalTab`/`sftppty`/`DatabaseManager.SFTP` из оригинала | `upstream/2.x` |
| **Без писка** — `AuditoryCues.playList = null` | `ApplicationRunner`, `WelcomePanel` |
| **Старт без проблем с JDK** — `start.bat` ставит `JBR 25`, `foojay 1.0.0` | `start.bat` |
| **Миграция из Termius** — хосты, вложенные группы, ключи и сниппеты | `tools/termius-to-termorator`, `TermiusMigration` |

---

## ✨ Возможности

- Windows / macOS / Linux, менеджер ключей, X11, SSH-Agent, System info, Nvidia SMI
- SFTP `A↔B`, рекурсия, 6 потоков, `rm -rf`, поиск, сниппеты

## 🔄 Миграция из Termius

Экспортёр переносит SSH-хосты с группами, пароли, обычные SSH-ключи, прокси, пробросы
портов, сниппеты и пакеты сниппетов. Подробный запуск описан в
[`tools/termius-to-termorator/README.md`](tools/termius-to-termorator/README.md). Полученный
JSON импортируется через контекстное меню папки: **Импорт → Termius**.

## 📦 Скачать

- **Termorator (форк):** [king-of-Jupiter/termora — Releases](https://github.com/king-of-Jupiter/termora/releases/latest)
- Оригинал: [TermoraDev/termora](https://github.com/TermoraDev/termora/releases/latest)

## 🛠️ Разработка

```bat
start.bat
gradlew.bat run
```

Требуется [JBR 25](https://github.com/JetBrains/JetBrainsRuntime). SSH-ядро — из `upstream/2.x`.

## 📄 Лицензия

`AGPL-3.0` — можно форкать/переименовывать, оставляя ссылку на `TermoraDev/termora` и исходники под AGPL. `THIRDPARTY` сохранён. Для закрытия — proprietary у автора.
