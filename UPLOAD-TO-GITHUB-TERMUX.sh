#!/data/data/com.termux/files/usr/bin/bash
set -e

cd "$(dirname "$0")"

if ! command -v git >/dev/null 2>&1; then
  echo "Git не установлен. Выполни: pkg install git gh"
  exit 1
fi
if ! command -v gh >/dev/null 2>&1; then
  echo "GitHub CLI не установлен. Выполни: pkg install gh"
  exit 1
fi

if ! gh auth status >/dev/null 2>&1; then
  echo "Сейчас откроется безопасная авторизация GitHub через браузер."
  gh auth login
fi

read -r -p "Вставь HTTPS-ссылку пустого GitHub-репозитория (например https://github.com/USER/xpay-android.git): " REPO
if [[ ! "$REPO" =~ ^https://github\.com/[^/]+/[^/]+(\.git)?$ ]]; then
  echo "Ссылка выглядит неправильно. Нужна HTTPS-ссылка на репозиторий GitHub."
  exit 1
fi

if [ ! -d .git ]; then
  git init
fi
git add -A
if ! git diff --cached --quiet; then
  git commit -m "Upload Xpay Android project"
fi
git branch -M main
if git remote get-url origin >/dev/null 2>&1; then
  git remote set-url origin "$REPO"
else
  git remote add origin "$REPO"
fi

echo "Отправляю весь проект на GitHub..."
git push -u origin main

echo "Готово. Открой репозиторий на GitHub и перейди во вкладку Actions для сборки APK."
