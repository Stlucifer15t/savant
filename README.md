# Savant

Savant is an Android “workbench chat” for inspecting and editing game save files. It parses saves with deterministic Kotlin code, stages typed changes as a diff, validates a rebuilt file, and exports a new copy. The selected source is never modified in place.

> **Milestone status:** this repository currently implements build steps **1–3 only**: project/theme/navigation, Storage Access Framework import with JSON and XML parsing, inline editing, diff confirmation, validation, and export. The later items in the product brief remain deliberately unwired.

## What works now

- Jetpack Compose + Material 3 UI with custom dark/light palettes, 6 dp shapes, bundled Space Grotesk and JetBrains Mono fonts, and line icons
- MVVM state with Hilt, coroutines, and `StateFlow`
- SAF `OpenDocument` import—no broad storage permission
- Android **Share to Savant** through `ACTION_SEND`
- 50 MB hard file-size limit and all reading/parsing/writing off the main thread
- format detection using filename extension and structural sniffing
- deterministic JSON and hardened XML parsers
- typed field tree, category heuristics, search, and category filters
- inline value editing with validation and quick values
- deterministic local command grammar, for example `set gold to 999999`
- mandatory old → new diff before edits reach the working tree
- post-write parse verification before an export is offered
- Share and Save-to-folder export flows
- app-private protected copy of the imported original for the active session
- first-launch warning for offline, single-player use only
- no `INTERNET` permission in this milestone

## Current format support

| Format | Detection | Parse/write | Notes |
| --- | --- | --- | --- |
| JSON | extension + `{`/`[` structure | yes | objects, arrays, string, signed 64-bit integer, finite double, boolean, null |
| XML / plist-like XML | extension + XML structure | yes | elements, attributes, repeated elements, text, CDATA; DTDs/entities disabled |

INI/CFG, RPG Maker MV/MZ, SQLite, Unity-specific PlayerPrefs handling, binary value scanning, checksums, encryption, and plugin-driven mappings are later milestones.

## Build and run

### Requirements

- Android Studio Ladybug or newer
- JDK 17
- Android SDK 35

### Android Studio

1. Open the repository root in Android Studio.
2. Let Gradle sync.
3. Select an Android 8.0+ device or emulator.
4. Run the `app` configuration.

### Command line

```bash
# local.properties should point at your Android SDK if ANDROID_HOME is not set
printf 'sdk.dir=%s\n' "$HOME/Android/Sdk" > local.properties

./gradlew testDebugUnitTest
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Using the app

1. Tap the outlined **drop a save file** area or the attachment icon. Alternatively, share a file to Savant from another Android app.
2. Savant reads the document, detects a parser, protects the original, and lists editable scalar values by category.
3. Tap a value and choose **preview**, or type a command such as `set gold to 999999`.
4. Review every proposed change. Revert individual rows, cancel, or apply.
5. Savant writes and reparses the working tree. Only a verified result gets a Share / Save card.

`max all stats` is accepted only when fields have declared maximums. Raw JSON/XML has no trustworthy limits, so Savant refuses to invent them.

## Architecture

```text
app/src/main/java/dev/savant/
├── ai/                 # provider boundary reserved for step 5
├── data/file/          # SAF I/O, share cache, session-original protection, UI preferences
├── di/                 # Hilt graph
├── domain/model/       # typed field tree, categories, validation, diffs
├── domain/parser/      # SaveParser, confidence, checksum hook, registry
├── mcp/                # remote MCP boundary reserved for step 9
├── parsers/json/       # deterministic JSON codec
├── parsers/xml/        # hardened XML codec
└── ui/
    ├── chat/           # workbench chat, field list, diff and export
    ├── components/     # custom line icons
    ├── files/          # current-session file state
    ├── formats/        # installed parser list
    ├── navigation/     # app shell and navigation
    ├── settings/       # local controls and future integration status
    └── theme/          # color, type and motion system
```

The LLM boundary is intentionally empty in this milestone. Parsers own all byte transformations, and this separation will remain when tool calling is added.

## Adding a parser

Implement `SaveParser`:

```kotlin
class ExampleSaveParser : SaveParser {
    override val id = "example"
    override val displayName = "EXAMPLE"
    override val extensions = setOf("example")
    override val checksumStrategy = NoChecksumStrategy

    override fun detect(bytes: ByteArray): Confidence = TODO()
    override fun parse(bytes: ByteArray): FieldTree = TODO()
    override fun write(tree: FieldTree): ByteArray = TODO()
}
```

Parser rules:

1. `detect` must be cheap and side-effect free. Use magic bytes or structure, not only an extension.
2. `parse` must reject corrupt input with `CorruptSaveException` and produce stable, unique paths.
3. Container nodes use `OBJECT` or `LIST`; only typed scalar nodes are editable.
4. Preserve enough non-executable metadata on nodes to rebuild ordering and structure.
5. `write` must validate `tree.formatId`, apply the format’s deterministic encoding, and never touch a source URI.
6. Add the parser to `AppModule.provideParserRegistry`.
7. Add round-trip tests: `parse → write → parse` must preserve the normalized field tree. Also test corruption and detection.
8. Add a real `ChecksumStrategy` when required. A writer must apply it before Savant performs its verification parse.

## Plugin document draft

Plugin installation is step 8 and is **not enabled** in this build. The planned documents are declarative JSON only; they cannot contain scripts or executable code. Two draft examples live in [`docs/plugins`](docs/plugins):

- `sample-rpg-json.json`
- `sample-unity-xml.json`

A mapping has this shape:

```json
{
  "schemaVersion": 1,
  "gameName": "Example Game",
  "fileMatch": {
    "extensions": ["json"],
    "rootKeys": ["player"]
  },
  "parser": "json",
  "mappings": [
    {
      "path": "$.player.gold",
      "name": "Gold",
      "type": "int",
      "min": 0,
      "max": 999999,
      "category": "currency"
    }
  ],
  "checksum": { "method": "none" }
}
```

Planned allowed parser values are `json`, `xml`, `ini`, `rpgmaker`, and `sqlite`. Planned field types are `int`, `float`, `bool`, `string`, `enum`, `list`, and `object`; categories are `currency`, `stats`, `inventory`, `flags`, and `other`. Step 8 will ship and enforce a JSON Schema before any imported/downloaded plugin can be offered for installation.

## Tests

The JVM test suite covers:

- JSON detection, corruption, typed edits, and normalized round trip
- XML detection, repeated elements, attributes, CDATA, typed edits, normalized round trip, and XXE rejection
- registry selection and unknown binary rejection
- edit type/range validation

Run it with:

```bash
./gradlew testDebugUnitTest
```

CI also assembles the debug APK.

## Safety and privacy

- Savant requests no broad storage permission.
- The source URI is read only. Exports always use a new document or a temporary read-only share URI.
- A private original copy is created before editing is enabled. Persistent Room indexing, history, and one-tap restore are build step 4.
- XML DTDs and external entities are disabled.
- This milestone has no Android internet permission and cannot make outbound calls.
- When providers are implemented, keys/tokens/auth headers will use Android Keystore-backed `EncryptedSharedPreferences`; they will never enter Room, logs, plain preferences, or exported settings.

## Deliberate choices for steps 1–3

- **Minimum Android 8.0 (API 26), target/compile SDK 35.** This keeps modern crypto/UI APIs available while covering Android 11+ SAF behavior.
- **Room is deferred to step 4.** An atomic app-private source copy protects the active session now; the Files screen says clearly that persistent history/restore is not yet present.
- **No provider form stores secrets yet.** Rendering a fake or plaintext API-key flow would violate the storage rules. Settings shows integration status, while appearance and warning replay are functional.
- **No network permission yet.** AI, search, MCP, network logging, encrypted provider profiles, and exact test-connection errors belong to steps 5 and 9.
- **Local natural-language support is intentionally narrow.** `set <field> to <value>` is deterministic; it never guesses ambiguous fields. Full tool calling is step 5.
- **XML round trips are structural, not byte-identical.** Insignificant formatting/comments may change, while element order, attributes, text/CDATA, namespaces, and typed editable values are represented. A rebuilt document must parse successfully before export.
- **No inferred numeric limits.** Without a trustworthy plugin, Savant validates numeric type and machine range but does not invent a game-specific max.

## Roadmap boundary

The requested build order remains the roadmap: Room backups/restore (4), encrypted multi-provider LLM tool calling (5), additional parsers (6), diff value finder (7), schema-validated plugins (8), web/MCP and network log (9), then onboarding/error-state polish (10).

## Font licenses

Savant bundles Space Grotesk and JetBrains Mono under the SIL Open Font License. License texts are in `docs/OFL-Space-Grotesk.txt` and `docs/OFL-JetBrains-Mono.txt`.
