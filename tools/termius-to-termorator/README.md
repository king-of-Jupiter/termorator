# Termius → Termorator

Утилита офлайн экспортирует из локального профиля Termius:

- SSH-хосты и вложенные группы;
- логины, пароли, прокси и пробросы портов;
- обычные SSH-ключи и привязку ключей к хостам;
- сниппеты и пакеты сниппетов.

Чтение Chromium IndexedDB, расшифровка полей и доступ к системному хранилищу ключей
делегированы проверенному MIT-проекту
[`y01and3/termius-export`](https://github.com/y01and3/termius-export), зафиксированному на
коммите `d1a34e9bbf1dcf63dbcb910cfc24d54460a287d0`. Termius не запускается и его данные не
изменяются.

## Запуск

Нужны Python 3.11+ и Git. Перед экспортом закройте Termius.

### Windows PowerShell

```powershell
cd tools/termius-to-termorator
py -3.12 -m venv .venv
.\.venv\Scripts\python -m pip install .
.\.venv\Scripts\termorator-termius-export
```

### macOS / Linux

```bash
cd tools/termius-to-termorator
python3 -m venv .venv
./.venv/bin/pip install .
./.venv/bin/termorator-termius-export
```

Результат: `termius-termorator-export/termorator-termius.json`.

В Termorator нажмите правой кнопкой по папке назначения и выберите
**Импорт → Termius**, затем укажите этот JSON.

Если профиль не найден автоматически:

```powershell
termorator-termius-export --data-dir "$env:APPDATA\Termius"
```

Для резервной копии без секретов используйте `--no-secrets`. Такой файл перенесёт структуру,
но не пароли и приватные ключи.

## Безопасность

JSON по умолчанию содержит пароли, приватные ключи и passphrase открытым текстом. Каталог
экспорта получает ограниченные права доступа средствами исходного проекта, но файл всё равно
следует удалить сразу после успешного импорта. Внутри Termorator данные сохраняются через его
штатные менеджеры и шифруемую базу.

