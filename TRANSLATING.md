# Translating Waypointer

Waypointer uses Minecraft's built-in language system. The canonical catalog is
[`src/main/resources/assets/waypointer/lang/en_us.json`](src/main/resources/assets/waypointer/lang/en_us.json).
No translation library, generated source, or Waypointer-specific language
selector is involved. Minecraft's resource manager automatically selects the
matching locale and falls back to `en_us` for keys that are not overridden.

## Adding or changing text

- Put user-facing copy in `en_us.json`, then render it with
  `Component.translatable("waypointer.<area>.<name>")`.
- Use lowercase dotted keys. Keep serialized IDs, command syntax, file-format
  tokens, and user-provided route or waypoint names unchanged.
- Pass dynamic values as component arguments instead of concatenating them:
  `Component.translatable("waypointer.route.progress", current, total)`.
- Use `%s` in English for arguments. A translation may reorder them with
  positional placeholders such as `%2$s` and `%1$s`. Minecraft translation
  components do not support printf conversions such as `%d` or `%f`.
- Prefer indexed placeholders (`%1$s`, `%2$s`) whenever a message has two or
  more arguments, so translations can safely change their order.

Settings keys are derived from their stable catalog IDs:

- `waypointer.settings.category.<category-id>`
- `waypointer.settings.group.<category-id>.<group-slug>`
- `waypointer.settings.setting.<setting-id>.label`
- optional `.tooltip`, `.color_picker_title`, and `.color_swatch_tooltip`
- `waypointer.settings.setting.<setting-id>.option.<zero-based-index>` for enum values

## Adding a locale

Copy `en_us.json` to an exact Minecraft locale filename such as `de_de.json`,
translate only the values, and keep the file UTF-8. Minecraft 26.2 exposes 142
locales, but Waypointer does not need to include untranslated copies of them:
add only locales that have a real translation. Every JSON file in the language
directory is treated as a supported locale and must contain exactly the
canonical keys with matching placeholder arguments. Blank values and duplicate
keys are rejected.

Run the catalog checks before submitting:

```powershell
./gradlew.bat test --tests com.babbur.waypointer.i18n.TranslationCatalogTest
```
