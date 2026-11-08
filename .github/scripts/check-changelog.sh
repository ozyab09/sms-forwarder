#!/usr/bin/env bash
# .github/scripts/check-changelog.sh
# Проверяет, что в секции [Unreleased] CHANGELOG.md есть записи для всех
# кодовых коммитов после последнего тега (issue #___).

set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
CHANGELOG="$REPO_ROOT/CHANGELOG.md"

if [ ! -f "$CHANGELOG" ]; then
  echo "::error::CHANGELOG.md not found" >&2
  exit 1
fi

# Последняя выпущенная версия
LAST_TAG=$(git describe --tags --abbrev=0 HEAD~1 2>/dev/null || echo "")

if [ -z "$LAST_TAG" ]; then
  echo "No previous tag found — skipping changelog check" >&2
  exit 0
fi

echo "Последний релизный тег: $LAST_TAG"

# Собираем все не-merge-коммиты с кодовыми изменениями от прошлого тега до HEAD
COMMITS=$(git log "${LAST_TAG}..HEAD" --no-merges --format=%H)

CODE_COMMITS=""
for hash in $COMMITS; do
  FILES=$(git diff-tree --no-commit-id --name-only -r "$hash")
  # Если есть хоть один файл вне *.md/.github/ — это код
  NON_BUILDABLE=$(echo "$FILES" | grep -v -E '(^|\.)(md|markdown)$|^\.github/' || true)
  if [ -n "$NON_BUILDABLE" ]; then
    CODE_COMMITS="$CODE_COMMITS $hash"
  fi
done

if [ -z "$CODE_COMMITS" ]; then
  echo "Нет коммитов с изменением кода после $LAST_TAG — чек пропущен" >&2
  exit 0
fi

echo "Найдено ${#CODE_COMMITS[@]} коммит(ов) с изменением кода:"
for hash in $CODE_COMMITS; do
  MSG=$(git log -1 --format='%s' "$hash")
  echo "  - $MSG ($hash)"
done

# Парсим секцию [Unreleased] из CHANGELOG
UNRELEASED=$(awk '/^## \[Unreleased\]/{found=1; next} /^## \[/{found=0} found{print}' "$CHANGELOG")

if [ -z "$UNRELEASED" ]; then
  echo "::error::Секция [Unreleased] пустая или отсутствует" >&2
  exit 1
fi

# Для каждого кодового коммита проверяем, есть ли упоминание в Unreleased
FAILED=0
for hash in $CODE_COMMITS; do
  SHORT_HASH=$(git rev-parse --short "$hash" 7)
  SUBJECT=$(git log -1 --format='%s' "$hash")

  # Ищем ключевые слова из сообщения коммита в Unreleased
  KEYWORDS=$(echo "$SUBJECT" | sed 's/(.*//g' | tr -s '[:space:]' '|' | sed 's/^|//;s/|$//' | head -c 80)

  FOUND=$(echo "$UNRELEASED" | grep -iE "$KEYWORDS" || true)
  if [ -z "$FOUND" ]; then
    echo "::warning::Не найдена запись в Unreleased для: $SUBJECT ($SHORT_HASH)"
    FAILED=$((FAILED + 1))
  else
    echo "  ✓ Найдено: $(echo "$FOUND" | head -1 | cut -c1-80)"
  fi
done

if [ "$FAILED" -gt 0 ]; then
  echo "::error::$FAILED коммит(ов) без записи в Unreleased changelog" >&2
  exit 1
fi

echo "Все кодовые изменения отмечены в CHANGELOG.md ✓"
