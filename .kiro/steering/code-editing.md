# Code editing conventions

## Insert code first, then add the import (never the reverse)

This workspace runs an on-save formatter that sorts imports and **strips any
import it considers unused at that instant**. If you add an `import` line before
the code that references the symbol exists, the formatter can delete the import
before the build ever sees it, producing confusing "unresolved reference" or
"illegal annotation" errors.

**Always follow this order when introducing a new symbol:**

1. First write (or edit) the code that *uses* the symbol — the function call,
   annotation, type reference, etc.
2. **Then**, as a separate follow-up edit, add the matching `import` line.
3. After adding imports, re-read the top of the file to confirm the import
   persisted before running the build.

Rationale: once the usage is present, the import is "used" and the formatter
will keep and correctly order it.

### Applies to
- Kotlin / Java (this project's primary languages)
- Any language whose tooling has an auto-remove-unused-imports or
  organize-imports-on-save behavior.

### Example (Kotlin)
- Add `@Serializable` to a data class **first**, then add
  `import kotlinx.serialization.Serializable`.
- If imports go missing after an edit, this ordering is almost always the cause —
  re-add the import and rebuild rather than assuming the dependency is missing.
