# savant

an android app that edits game save files through a chat-shaped workbench.

attach a save, savant detects the format, parses it, and reports what it found. change a value by
tapping it or by typing `set gold to 999999`. every change goes through a diff preview, and the
file is rebuilt, re-parsed and verified before savant offers an export. the original is never
touched.

parsing and writing are deterministic kotlin. an assistant can *propose* edits; it never writes a
byte.

---

## build status

| step | scope | state |
| --- | --- | --- |
| 1 | project, theme, navigation, chat shell | **done** |
| 2 | file import, json + xml parsers, field list in chat | **done** |
| 3 | edit flow, diff preview, rebuild + verify + export | **done** |
| 4 | room database: history, sessions, search | **done** |
| 5 | value finder across two saves, typed binary scanner | **done** |
| 6 | ai providers, profiles, privacy controls | planned |
| 7 | plugin runtime: import/export game definitions | planned |
| 8 | mcp connectors, web search, fetch | planned |
| 9 | lz-string / rpgsave, sqlite, ini, unity plist | planned |

everything below describes what is in the repository today.

---

## setup

requirements

- android studio ladybug (2024.2) or newer
- jdk 17
- android sdk: compile/target 35, min 26

```bash
git clone <this repo> savant
cd savant
./gradlew :app:assembleDebug        # build
./gradlew :app:installDebug         # install on a device or emulator
./gradlew :app:testDebugUnitTest    # 123 unit tests, pure jvm
```

### or just install the apk

every push to the working branch builds a debug apk on github runners and
republishes it to one rolling release, so the url never changes:

**https://github.com/Stlucifer15t/savant/releases/download/savant-debug/app-debug.apk**
(~12 mb, debug-signed, `dev.savant.debug`)

on the phone: allow "install unknown apps" for the browser or file manager, open
the apk, install. the same build is also kept as a workflow artifact
(`savant-debug-apk`, 14 days) under the run's summary. `.github/workflows/build-apk.yml`
runs the unit tests after the apk is published, so a test failure never blocks
the download.

versions are pinned in `gradle/libs.versions.toml`: agp 8.7.3, kotlin 2.0.21, ksp 2.0.21-1.0.28,
hilt 2.52, compose bom 2024.10.01, gradle wrapper 8.11. nothing in the build needs a newer
toolchain, and no version was chosen that could not be resolved.

first launch shows one warning: savant is for offline, single-player saves. writing to a save used
by an online game, a server or an account with anti-cheat can corrupt the save and can get the
account banned. it has to be acknowledged once and can be re-read in settings → about.

---

## the flow

1. **attach** — the picker (`ACTION_OPEN_DOCUMENT`) or share-to-app (`ACTION_SEND`). no storage
   permission is requested anywhere, and android 11+ `Android/data` restrictions are respected: if
   a file cannot be reached, the user shares it in.
2. **detect** — `FormatDetector` looks at magic bytes, extension and text shape; every registered
   parser then scores the file. best score wins, ties are reported, and a parse failure caps the
   score so a wrong guess never looks certain.
3. **parse** — on `Dispatchers.IO`, up to 50 mb, up to 25 000 field nodes. the chat prints a
   monospace log line per stage, staggered 30 ms apart.
4. **report** — a file chip (name, size, format badge, confidence, hex thumbnail), then
   `found N editable values` and the values grouped as currency / stats / inventory / flags / other.
5. **edit** — tap a value to edit it inline (`MAX` / `0` / `x2` chips for numbers), or type a
   command. both produce *proposals*.
6. **diff** — old value struck through, new value in the accent colour, one revert per row. apply
   or cancel; the resolved card stays in the transcript.
7. **rebuild** — the parser re-serialises its own document model (whitespace, quoting, entity
   encoding, attribute order preserved), re-encodes any codec, recomputes any checksum.
8. **verify** — the rebuilt bytes are parsed again and every applied edit is checked in the result.
   a file that fails verification cannot be exported.
9. **export** — share sheet, or save to a folder the user picks (`ACTION_CREATE_DOCUMENT`). the
   output is always a new file named `<name>.edited.<ext>`.

a backup of the untouched original is written to internal storage before the first edit. files →
backups → restore reopens it in one tap.

---

## screens

- **chat** — the workbench. header shows file name, format badge, backup dot and pending change
  count. message list, flat input bar with a 1 px top border. a cold start shows the last
  transcript again, marked as history; long press a field name to save a name for a cryptic key.
- **files** — the open file, backups, recent imports, and history: every session savant opened plus
  a search box over the stored transcripts. restore, delete, reopen, view, clear.
- **formats** — what parses today, what is planned, the codec/checksum matrix, and the plugin
  schema written down ahead of the runtime.
- **finder** — three modes behind one bar. **open file** searches the parsed tree by name, path,
  value, `category:flags` or `type:f32`, and expanding a hit shows the full field record and hands it
  to the chat. **compare saves** takes two saves of the same game and ranks every typed value that
  moved. **scan bytes** takes one file and a value you know is in it, and lists every offset that
  holds it. the byte modes need no parser, and a candidate can be named.
- **settings** — appearance, editing behaviour, ai provider (inert until step 6), privacy, data,
  about.

---

## architecture

```
app/src/main/java/dev/savant/
├── MainActivity.kt          one activity, edge to edge, share-intent handoff
├── MainViewModel.kt         theme + pending share uri
├── SavantApplication.kt     @HiltAndroidApp
├── ai/                      assistant contract, tools, tool executor, local assistant
├── codecs/                  CodecStrategy, ChecksumSpec, ByteRangeSpec
├── data/
│   ├── db/                  room entities, daos, SavantDatabase (schema in app/schemas)
│   ├── history/             ChatHistoryRepository, EntryCodec, NamedFieldRepository
│   ├── session/             SessionStore — the one open save, shared by every screen
│   ├── settings/            SavantSettings, SettingsRepository (DataStore), SecretStore
│   └── storage/             SaveFileRepository (SAF), BackupStore + ImportIndex, room stores
├── di/                      Hilt modules + dispatcher qualifiers
├── domain/
│   ├── chat/                chat entry models (text, file, parse, fields, diff, export, notice)
│   ├── finder/              BinaryValues, Candidate, ScanSpec, SaveDiff — two save diff and scan
│   ├── model/               Node, FieldTree, FieldValue, FieldType, FieldPath, FieldEdit,
│   │                        FieldCategory, Confidence, SaveFormat, ImportedFile, AiGuess
│   ├── parser/              SaveParser, SaveDocument, ParserRegistry, ExportVerifier, limits
│   └── session/             EditorSession, SaveEditor, ExportArtifact
├── parsers/
│   ├── FormatDetector.kt    magic bytes, text shape, blockers
│   ├── categorize/          CategoryClassifier, Humanizer
│   ├── json/                hand written reader, writer, tree builder, parser, JsonPayload
│   └── xml/                 hand written reader, writer, values, tree builder, parser
└── ui/
    ├── SavantApp.kt         NavHost + bottom bar
    ├── theme/               colour, type, shape, motion
    ├── icons/               SavantIcons (54 thin outline drawables)
    ├── components/          FileChip, ParseProgress, FieldGroupCard, DiffCard, ExportCard,
    │                        CandidateRow, EmptyState, Messages, Primitives, TopBars, ValueTicker
    ├── chat/                ChatScreen, ChatViewModel
    ├── files/  formats/  settings/
    ├── finder/              FinderScreen, FinderViewModel, ScanPanel (compare + scan modes)
```

`domain/`, `parsers/`, `codecs/` and the parsing half of `ai/` are pure jvm: no android imports, no
compose, no room. that is why they can be unit tested directly on the host. `data/history/EntryCodec`
is deliberately in that group too — it turns a chat entry into a stored row and back without knowing
room exists, so the encoding rules are testable.

---

## domain contracts

```kotlin
interface SaveParser {
    val format: SaveFormat
    fun detect(bytes: ByteArray, fileName: String?): Confidence
    fun parse(request: ParseRequest): ParseResult
    fun write(document: SaveDocument, fileName: String? = null): ByteArray = document.toBytes()
}

interface SaveDocument {
    val format: SaveFormat
    val parser: SaveParser
    val tree: FieldTree
    val originalSize: Int
    fun applyEdits(edits: List<FieldEdit>): ApplyResult
    fun toBytes(): ByteArray
}
```

a `Node` is one field: `path`, `displayName`, `type`, `value`, `category`, `editable`,
`endianness`, `min`, `max`, `byteOffset`, `byteLength`, `enumOptions`, `aiGuess`, `children`,
`sourceKey`, `fixedWidth`. `FieldTree` is immutable, indexes every node by path, and is replaced
(not mutated) after each confirmed batch — which is what makes undo a list operation.

`FieldPath` encodes and parses paths (`player.gold`, `party[0].hp`, `` `weird.key` ``); quoting only
kicks in for keys containing `. [ ] ` \` or whitespace, so xml keys like `#text` and `@version` stay
readable.

`EditValidator` validates and coerces before an edit exists: type fit, min/max, unsigned ranges, and
the `MAX` / `0` / `x2` / `/2` shortcuts. invalid input is reported in the chat with a reason instead
of being written.

`ExportVerifier.verify(rebuilt, fileName, edits, parser, expectedFormat)` re-parses the output and
returns a `VerificationReport(ok, format, sizeBytes, checkedEdits, mismatches, warnings,
reparsedReadOnly)`. the export card renders that verdict verbatim.

limits, in `NodeLimits`: 50 mb file, 25 000 nodes, depth 48, 400 nodes before the ai payload is
trimmed, 200 kb before values are stripped from it.

---

## how to add a parser

1. **add the format** if it is not in `SaveFormat` yet (`domain/model/SaveFormat.kt`): label, badge,
   extensions, `implemented = true`, and a one-line note that the formats screen shows.
2. **write the reader** in `parsers/<format>/`. keep the document model faithful — savant must be
   able to reproduce bytes it did not change. for text formats that means remembering whitespace,
   quoting style and entity encoding; for binary formats it means keeping the original byte array
   and patching offsets instead of re-serialising.
3. **build the tree** with `FieldTreeBuilder`-style code: one `Node` per leaf, `category` from
   `CategoryClassifier.classify(...)`, `displayName` from `Humanizer.humanize(key)`, `type` from the
   value you found, `byteOffset` when you know it.
4. **implement `SaveDocument`** so `applyEdits` writes into the model and `toBytes()` re-encodes:
   codec first (`Codecs.byId(hints.codec)`), then checksum (`ChecksumSpec.apply(bytes)`).
5. **detect honestly.** return a `Confidence` built from what you actually saw, and score a failed
   probe parse low. `ParserRegistry` picks the best candidate; a parser that over-claims steals files
   from the right one.
6. **register it** in `di/CoreModule.kt`:
   `ParserRegistry(listOf(JsonSaveParser(), XmlSaveParser(), YourParser()))`.
7. **test it** in `app/src/test/java/dev/savant/parsers/<format>/`. the bar is a round trip:
   `parse(bytes).document.toBytes()` equals the original bytes for a file savant did not edit, and
   `parse(write(parse(bytes)))` equals `parse(bytes)` after an edit. see
   `JsonSaveParserTest` and `XmlSaveParserTest` for the pattern, and `CodecRoundTripTest` for two
   small sample binaries with header and trailer checksums.

a parser that cannot promise a faithful rebuild should say so: return a tree with `readOnlyReason`
set. savant opens it read only, shows the values, blocks the diff, and prints the reason — which is
exactly what happens today when a file is sniffed as gzip or zlib but no plugin declared the codec.

---

## how to write a plugin

the plugin runtime lands in step 7. the schema is fixed now so a game can be described before the
importer exists, and the formats screen shows the same document.

```json
{
  "gameName": "example quest",
  "fileMatch": ["save*.dat"],
  "parser": "binary",
  "codec": "zlib",
  "checksum": { "strategy": "crc32", "range": [4, -1], "storedAt": [0, 4] },
  "fields": [
    { "path": "player.gold",  "offset": 16, "type": "u32" },
    { "path": "party[*].hp",  "pointer": 40, "stride": 24, "type": "i16" }
  ]
}
```

- `gameName`, `fileMatch` — display name and glob patterns used during detection.
- `parser` — `json`, `xml`, `ini`, `rpgsave`, `sqlite`, `plist` or `binary`.
- `codec` — `none`, `gzip`, `zlib`, `xor` (+ `xorKey`), later `lzstring`, `aes`.
- `checksum` — `strategy` (`crc32`, `adler32`, `md5`, `sha1`, `sha256`), `range` as
  `[start, length]` with `-1` for "to the end", `storedAt` as `[offset, width]`, plus `endianness`
  and `storedAsHexText` for text formats. this maps directly onto `ChecksumSpec`.
- `fields` — path-based mappings for structured formats, or binary mappings with `offset`, `type`
  (`i8…u64`, `f32`, `f64`, `bool`, `str`), `endianness`, `length`, and for arrays a `pointer` plus
  `stride`.
- `rename` — optional display names for cryptic keys; savant marks them as ai guesses either way.

rules the runtime will enforce: a plugin describes data, it is never executed; it cannot request
network access; imports are shown as json before they are accepted; and a plugin-declared codec is
the only way a compressed file becomes writable.

---

## codecs and checksums

`codecs/CodecStrategy.kt` — `IdentityCodec`, `GzipCodec`, `ZlibCodec`, `XorCodec(key)`, with
`Codecs.sniff(bytes)` ranking what a blob looks like. `changesLength` tells the editor whether a
byte offset in the tree still means anything after decoding.

`codecs/ChecksumStrategy.kt` — `Crc32`, `Adler32`, `Md5`, `Sha1`, `Sha256` over a `ByteRangeSpec`
(start, length, exclusions, seed), stored at an offset or at a field path, in either endianness, as
raw bytes or hex text, optionally truncated. `apply` recomputes and writes; `matches` checks.

---

## ai layer

`AssistantEngine` is the provider-agnostic contract: `respond(AssistantInput)` returns either
`Text`, `Proposal(edits, requestExport)` or `Failure`. two implementations are planned; one exists.

- `LocalAssistant` (today) — a deterministic command parser in front of the tool executor. no key,
  no network, no model. it understands `set x to y`, `x = y`, `max all stats`, `zero flags`,
  `double gold`, `add 10 to strength`, `unlock tutorialdone`, `search hp`, `what is gold`,
  `explain v_0042`, `undo`, `export`, `list fields`.
- a provider-backed engine (step 6) — same contract, same tools, `isRemote = true`, keys in
  encrypted storage, request logging with keys redacted to their last four characters.

the eight tools, in `ai/Tools.kt`:

| tool | does |
| --- | --- |
| `list_fields` | counts and category summary |
| `search_fields` | name/path/value search, `category:` and `type:` filters |
| `get_field` | one field, its siblings and its constraints |
| `set_field` | propose one value change |
| `set_many` | propose a batch (max 2000), e.g. every stat |
| `undo` | propose the reverse of the last applied batch |
| `export` | ask for a rebuild |
| `explain_field` | rename a cryptic key and mark it as a guess |

`ToolExecutor` is the only execution path, and the value-changing tools return **proposed**
`FieldEdit`s — the diff preview is the gate for a model exactly as it is for a tap. `AiPayload`
handles the privacy rules: over 200 kb only a trimmed field summary is sent, long text values are
dropped, and keys are redacted everywhere.

---

## the local database

room 2.6.1 through ksp, schema exported to `app/schemas` and committed — ci pushes it back to the
branch after a build, so a later version bump always has something to diff against.

| table | what it holds |
| --- | --- |
| `sessions` | one row per file savant opened: uri, name, format, size, node and edit counts, backup id, read-only reason, opened/closed timestamps |
| `entries` | one row per chat message, foreign key to its session with `on delete cascade` |
| `backups` | metadata only — the bytes stay at `files/backups/<id>/save.bin` |
| `imports` | recent files, so a session can be reopened without browsing again |
| `named_fields` | names given to cryptic keys, keyed by format plus the raw key |

an entry is stored as two things: a plain text `body`, which is what the search matches, and a
compact json `payload` for everything else. `EntryCodec` owns both directions.

**what is not stored, on purpose:** field values and rebuilt bytes. a transcript is a record of what
happened, not a copy of the save — values belong to the file, and pretending otherwise would show
stale numbers next to live ones. so a restored field card shows `currency 3 · stats 12` and says
"reopen the file to browse and edit its values", and a restored export card keeps its verification
verdict with the share and save buttons off.

the flat files step 3 used (`meta.txt` per backup, `imports.txt`) are read once by
`LegacyStoreMigration`, moved into the tables, and renamed so it cannot run twice. `BackupStore` and
`ImportIndex` kept their step 3 contracts, which is why no screen changed shape.

search is a plain sqlite `like` over the stored body — paths, values, both sides of a diff and
message text are all in there. no fts index, no network, no service.

---

## the value finder

For a format savant cannot parse, the value is still in there somewhere. Three things find it, and
none of them writes a byte.

**compare saves** — the workflow every cheat engine converged on, because it is the one that works:

1. play, change one thing (spend gold, take damage), save again;
2. hand savant both files. it looks *only* at the bytes that differ, interprets each changed stretch
   as every fixed width number in both byte orders, and ranks what it finds;
3. too many candidates? save a third time and narrow: what held its value is promoted, what moved
   again is kept, what went back to its old value is dropped as a transient;
4. name the winner.

scanning only the changed runs is what makes this usable on a big file — reading every offset of
every type in a 50 MB save would be 800 million reads to find four bytes that moved.

**scan bytes** — the single file case: you know gold is 1234, savant lists every offset that holds
it, as any of `i8 u8 i16 u16 i32 u32 i64 u64 f32 f64` in either byte order. Floats match with a
tolerance, because `42.5` typed by a human and `42.5` stored as a float32 are not the same bits.

**ranking** is conservative and always explains itself. Every candidate carries the reasons for its
percentage:

| signal | effect |
| --- | --- |
| the changed run is exactly this type's width | strongest positive |
| the changed bytes sit at the low edge for this byte order | counters grow from the low end |
| the offset is naturally aligned for the type | positive |
| plausible magnitude, small increase | positive |
| every byte in the window is printable ascii | probably part of a name |
| no zero bytes in the window | digest shaped — a checksum, not a counter |
| a float reading that is denormal or absurd | four bytes of an integer, misread |
| 64 bit integer | rare in saves |
| the two files differ in size | offsets are not comparable past the shorter one |

A checksum trailer is the honest test: it changes whenever anything else does, so it always shows up
in the list. The sample save in the tests has one, and the assertions are about the gold counter
outranking it rather than the checksum being hidden.

**naming a candidate** stores offset, type and byte order against a name, per format, in
`named_fields`. That row is the finder's only output, and it is what step 7 exports as a plugin field
mapping — so the work done here is not thrown away when the format gets a real parser.

---

## testing

```bash
./gradlew :app:testDebugUnitTest
```

179 tests, pure jvm, no robolectric and no android APIs:

- `domain/model/FieldModelTest` — paths, quoting, value parsing and formatting, types, categories,
  validation, quick values, tree search and normalisation.
- `parsers/DetectionAndNamingTest` — magic bytes, text shape, blocker messages, detection scoring,
  humanising and cryptic-key detection.
- `parsers/json/JsonSaveParserTest` — byte-exact round trips (compact and pretty), quoting styles,
  big integers, floats, unicode escapes, lenient inputs, node caps, corrupt files, gzip-wrapped
  json opening read only, edits and rebuilds, and utf-8: accented and cjk values read as text, an
  edit elsewhere leaves their bytes alone, and a truncated sequence still opens.
- `parsers/xml/XmlSaveParserTest` — prolog, doctype, comments, cdata, attributes, self-closing tags,
  entity escaping, plist dicts, whitespace fidelity, edits and rebuilds.
- `domain/session/SaveEditorTest` — the whole step 3 flow: open, propose, apply, reject, undo,
  rebuild, verify, export naming, read-only handling.
- `ai/LocalAssistantTest` — every command shape, ambiguity, failures, undo, export, the tool
  catalog, and the redaction and trimming rules.
- `codecs/CodecRoundTripTest` — two small sample binaries: a crc32 header over 20 bytes of payload,
  and an adler32 trailer over an xor-obfuscated body. checksum repair after an edit, gzip and zlib
  round trips, and a corrupt stream failing loudly.
- `data/history/EntryCodecTest` — what a transcript stores and what it must not: searchable bodies,
  diff rows carrying both sides, verdicts surviving, field values and rebuilt bytes deliberately
  gone, an unknown kind skipped instead of crashing, a corrupt payload falling back to its body.
- `parsers/json/JsonPayloadTest` — savant's own json: compact output, integers staying integers,
  string escaping, non-ascii text, tolerant accessors, and the rule that a stored string stays a
  string unless the field declares a type.
- `domain/finder/BinaryValuesTest` — typed reads in both byte orders, sign extension for narrow
  integers, float and double windows, windows that do not fit, offset and hex formatting, and the
  two shape tests ranking depends on: printable text, and no zero bytes.
- `domain/finder/SaveDiffTest` — the finder over two small sample saves shaped like a real one:
  magic header, i32 gold, f32 hp, i16 lives, an ascii name, a big endian play time and a crc32
  trailer that moves whenever anything else does. changed runs, ranking (the gold counter has to beat
  the checksum it disturbed, and a float reading of an integer has to lose), byte order, alignment
  flags, size mismatches, third save narrowing, exact value scans and two scale checks.

every parser test asserts the round trip in both directions: parse → write → parse equals the
original, and unedited bytes come back identical.

---

## design system

- **palette** — dark: background `#0E0F11`, surface `#16181B`, border `#26292E`, text `#E6E4DF`,
  muted `#8B8F96`, accent amber `#F2A33A`. light: warm off-white `#F7F4EE`, same accent darkened to
  `#B9741A` for contrast. the accent appears on changed values, the send button, the selected tab
  and the diff's new value — nowhere else. no purple, no blue, no gradient.
- **type** — space grotesk for ui copy, jetbrains mono for every value, path and log line. both are
  bundled as ttf. no roboto, no inter.
- **shape** — 6 dp radius, 1 px borders, no elevation, no shadow, no glass.
- **icons** — 54 thin outline glyphs on a 24 dp grid with a 2 dp stroke (feather family, the same
  lineage as lucide), as vector drawables. no sparkle, no magic wand, no "ai" badge.
- **motion** — a value change ticks to its new number and flashes the accent once for 400 ms; parse
  lines stagger 30 ms apart; everything else is under 300 ms. `reduced motion` in settings collapses
  all of it to final states.
- **copy** — short, factual, lowercase. no "sure! i'd be happy to help", no emoji, no mascot.
- **structure** — assistant replies are flat with a 2 dp accent rule; only the user's message is a
  bubble; the input bar is flat with a 1 px top border; the empty state is a dashed drop zone with
  one button.

---

## safety

- the original file is never modified. exports are new files, named `<name>.edited.<ext>`.
- a backup of the untouched original is taken before the first edit; restore is one tap.
- nothing is written until a diff is confirmed, and no export is offered before the rebuilt file has
  been re-parsed and every applied edit checked.
- min/max and type constraints are validated; a value that does not fit is reported, not clamped
  silently.
- no storage permission, no upload, no analytics. `INTERNET` is declared for the provider, search
  and mcp steps; today nothing uses it.
- parsing runs off the main thread with hard caps on file size, node count and depth.

---

## decisions and trade-offs

reasonable choices made without asking, so they are written down:

1. **json and xml parsers are hand written.** the alternatives (org.json, kotlinx.serialization,
   xmlpull) all normalise away the whitespace, quoting and entity details that a byte-faithful
   rebuild needs, and none of them are unit-testable on the host without android. the readers are
   lenient (bom, single quotes, trailing commas, comments, unquoted keys) and remember the style
   they saw.
2. **json integers are advisory `INT32`/`INT64`/`UINT64`**, never fixed width: a text format has no
   width to preserve, so an edit widens the type instead of being rejected. binary parsers will set
   `fixedWidth = true` and get strict validation.
3. **a sniffed codec makes a file read only.** if savant did not get the codec from a plugin or a
   hint, it cannot promise the rebuild is identical, so it says so and shows the values anyway. the
   message names the codec to declare.
4. **the local assistant is a deterministic command parser**, not a model. it keeps steps 1–3
   useful with no api key, and it exercises the exact tool path a provider will use later. the
   `AssistantEngine` contract is the seam.
5. **room is deferred to step 4.** backups and the recent-imports list are file backed
   (`files/backups/<id>/{save.bin,meta.txt}` and `files/imports.txt`) so step 3 has restore without
   a schema. the on-disk layout is deliberately boring so it can be lifted into tables unchanged.
6. **api keys live in memory only for now.** `SecretStore` is a process-scoped map; step 6 replaces
   it with `EncryptedSharedPreferences`. nothing is written to disk, and every surface shows a
   masked value.
7. **icons are feather-derived vector drawables** rather than `material-icons-extended` (a 7 000-icon
   dependency for ~50 glyphs) or lucide (not fetchable in this environment, same design lineage).
8. **fonts are bundled ttfs** rather than downloadable fonts: savant has to look identical offline
   and on devices without the play services font provider.
9. **`org.jetbrains.kotlin.plugin.serialization` is deliberately absent.** there is no runtime
   serialization jar in the pinned set yet and nothing needs it; the plugin json model arrives with
   step 7.
10. **document caps** — 1 000 000 json/xml values and 25 000 field nodes. a phone has to stay
    responsive, and a save that big is a database, not a save.
11. **exports always use `application/octet-stream`** in `ACTION_CREATE_DOCUMENT`; the file name
    still carries the right extension, and a per-mime contract instance would have to be recreated
    on every launch.
12. **toolchain pinned to what could be verified to exist.** newer releases (kotlin 2.4.x, gradle
    9.x, dagger 2.60) exist but were not resolvable here, so the build uses versions that are known
    to be on google maven and maven central.
13. **the app is built in ci, not in the dev sandbox.** the sandbox has no android sdk and no
    access to google maven, so while it was written the jvm half (domain, parsers, codecs, ai and
    their 123 tests) was compiled and run with kotlinc and a stub junit runner, and the android
    half was written against stable, non-experimental material 3 apis. `.github/workflows/build-apk.yml`
    is now the compiler of record: it builds the apk, publishes it, then runs the tests. the first
    real build caught four things the sandbox could not — a nullable `Uri` passed to
    `takePersistableUriPermission`, two missing compose imports, and a missing hilt `@Binds` for
    `BackupStore` — all fixed. because runner logs are served from a host the sandbox cannot reach,
    a failing build pushes its own gradle log to the `ci-logs` branch (`.ci/build.log`), which is
    readable through the ordinary contents api. a failing *test* also pushes the junit xml messages,
    because gradle's console line is only `AssertionError at File.kt:12`.
14. **history stores text, not values.** see [the local database](#the-local-database). the
    alternative — snapshotting the field tree per message — would make a transcript a second copy of
    every save on the phone and would show numbers that no longer match the file.
15. **a byte-based reader still has to decode utf-8 for values.** the json reader works on bytes so
    an untouched file is written back identically, and it originally turned each byte into a char.
    for ascii that is the same thing; for `José` it produced mojibake that was then re-encoded as
    nine bytes the moment any field in that file was edited. string values now decode real utf-8
    sequences, with overlong forms, surrogates and truncated sequences falling back to the raw byte.
16. **named fields are display only.** a saved name is laid over the parsed tree in the ui; the
    parser still reads and writes the real key, so a renamed field never changes the bytes.
17. **the finder interprets only the bytes that differ.** the obvious implementation — read every
    offset as every type — is 800 million reads on a 50 mb file and mostly noise; two saves of one
    game differ in a handful of places, so that is all savant looks at. a single file scan is the
    exception, and it is bounded by the value being searched for.
18. **a candidate's reasons are part of the result, not a debug aid.** the ranking is a set of
    heuristics, and heuristics are only useful if the user can see which ones fired and disagree
    with them. `Candidate.reasons` is rendered next to the percentage.
19. **float readings are judged on both sides of the change.** four bytes of a small integer read as
    a little endian float32 come out as a denormal (120 is 1.6e-43), and no game stores one on
    purpose. without that rule a float misreading of the gold counter outranked the gold counter.

---

## known limits

- json is byte-exact for compact and uniformly indented inputs. a file that mixes indentation styles
  comes back normalised to its dominant style.
- xml round trips byte-exactly except for whitespace *inside* tags, which is normalised on write.
- a `MAX` shortcut on a value with no declared maximum uses `2147483647`; a binary parser with a
  real type will use its own range.
- the byte finder ranks, it does not know. a candidate is a proposal with its reasons attached; the
  edit still has to go through a parser or, from step 9, an offset plugin. naming one stores the
  mapping but nothing writes through it yet.
- compare mode holds two files in memory at once, so it caps each at 32 mb (the parser's own cap is
  50 mb). a save bigger than that is a database, not a save.
- the byte finder cannot see a value that is compressed, encrypted or checksummed per block: if the
  codec changed the bytes, the diff shows the codec's output, not the field.
- history search is a substring match over stored text, not ranked full text search, and it looks at
  message bodies — a value that only ever appeared inside a field card is not in there, because
  field values are not stored.
- a restored transcript is read only. its export card keeps the verification verdict but not the
  bytes, so share and save are off until an edit rebuilds the file.
- api keys are still in memory only; encrypted storage arrives with the provider work in step 6.
- `Android/data` is unreachable on android 11+ without extra privileges; share the file in instead.
  an optional shizuku path is a later decision, not a plan.

---

## licence

none yet — this is a working repository, not a published project.
