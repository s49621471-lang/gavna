#!/usr/bin/env bash
# Lists every deliberately unimplemented surface in the codebase.
#
# ARCHITECTURE.md section 18 rule 1 requires unfinished work to be marked rather than
# described as done. This is the check that makes the rule enforceable: the output is
# the authoritative answer to "what does not work yet", and docs/STATUS.md is generated
# from it rather than written from memory.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"

printf '%s\n' "TODO markers with a phase:"
grep -rn --include='*.kt' --include='*.cpp' --include='*.h' --include='*.dart' \
    -E 'TODO\(phase-[0-9]+\)' "$root/core" "$root/app" "$root/ui/lib" 2>/dev/null \
    | sed "s|$root/||" | sort || true

printf '\n%s\n' "Surfaces that report themselves as unimplemented at runtime:"
grep -rn --include='*.kt' --include='*.cpp' \
    -E 'kNotImplemented|NOT_IMPLEMENTED|UnsupportedOperationException' \
    "$root/core" "$root/app" 2>/dev/null | sed "s|$root/||" | sort || true
