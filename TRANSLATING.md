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

Use an exact Minecraft locale filename such as `de_de.json` and keep the file
UTF-8. Locale files are sparse overlays. Add only keys that have a translation.
Do not copy a value that is identical to `en_us`; omit that key and Minecraft
will use the English fallback. An empty `{}` catalog is valid. Each included key
must exist in `en_us.json`, have a nonblank string value, and keep the same
placeholder arguments. Duplicate keys are rejected.

Keep all 142 Mojang locale files in `translations/lang` so the remote manifest
has stable locale coverage. A locale can remain `{}` until translations arrive.

Run the catalog checks before submitting:

```powershell
./gradlew.bat test --tests com.babbur.waypointer.i18n.TranslationCatalogTest
```
