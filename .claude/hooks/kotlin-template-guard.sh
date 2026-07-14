#!/bin/sh
# Blocks the ${'$'} Kotlin string-template corruption.
#
# WHY THIS EXISTS: writing Kotlin through a Python heredoc tempts you to "escape"
# the $ of a string template as ${'$'}. In Kotlin that is not an escape — it
# renders a LITERAL dollar sign followed by literal text. The file COMPILES
# PERFECTLY and fails only at runtime (a URL that contains the text
# "${AppConfig.serverBaseUrl}", a break label that reads "${time.hour...}").
#
# It has shipped broken code three times (2026-07-03, 2026-07-05, 2026-07-14).
# An advisory note in memory and in the kmp-developer skill did not stop it,
# because it is a momentum error, not a knowledge error. Hence a hook.
#
# Cheap by construction: only .kt files changed vs HEAD, plus untracked ones.
set -u

cd "${CLAUDE_PROJECT_DIR:-$PWD}" 2>/dev/null || exit 0
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || exit 0

PATTERN="{'\$'}"
HITS=""

FILES=$(
  {
    git diff --name-only HEAD -- '*.kt' 2>/dev/null
    git ls-files --others --exclude-standard -- '*.kt' 2>/dev/null
  } | sort -u
)
[ -n "$FILES" ] || exit 0

for f in $FILES; do
  [ -f "$f" ] || continue
  if grep -qF "$PATTERN" "$f" 2>/dev/null; then
    HITS="$HITS$f
"
  fi
done

[ -n "$HITS" ] || exit 0

{
  echo "BLOCKED — Kotlin string-template corruption: the literal text {'\$'} is present in:"
  echo "$HITS" | while IFS= read -r f; do
    [ -n "$f" ] || continue
    echo "  $f"
    grep -nF "$PATTERN" "$f" | head -3 | sed 's/^/        /'
  done
  echo
  echo "In Kotlin, \${'\$'} is NOT an escape — it renders a literal dollar sign plus literal"
  echo "text. It compiles fine and fails at runtime. This is what a Python heredoc writing"
  echo "Kotlin produces. Kotlin's \$ needs no escaping inside a Python string."
  echo
  echo "Fix each line above to use a plain \$ template, and prefer the Edit/Write tool over"
  echo "a shell heredoc for Kotlin containing \$."
} >&2
exit 2
