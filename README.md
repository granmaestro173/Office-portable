# Office Portable

An Android app that opens and **fully edits** Word, Excel and PowerPoint files,
completely offline. It is built on the ONLYOFFICE editing engine, the same one used
by ONLYOFFICE Docs, running on the device inside the app.

## Install

1. Open **[Releases](../../releases)** on your phone and download the newest `OfficePortable-*.apk`.
2. Tap the downloaded file. If Android asks, allow your browser or file manager to
   "install unknown apps".
3. Open **Office Portable**.

Requires Android 8.0 or newer and an up-to-date **Android System WebView**
(update it or Google Chrome from the Play Store if the app says so).

## What it does

| Kind | Open and edit | Save back as |
|---|---|---|
| Documents | `.docx` `.doc` `.odt` `.rtf` `.txt` `.dotx` `.docm` | DOCX, ODT, RTF, TXT, PDF |
| Spreadsheets | `.xlsx` `.xls` `.ods` `.csv` `.xltx` `.xlsm` | XLSX, ODS, CSV, PDF |
| Presentations | `.pptx` `.ppt` `.odp` `.ppsx` `.potx` | PPTX, ODP, PDF |
| PDF | view, annotate, fill forms | PDF |

- **Full editor**: styles, tables, images, headers and footers, track changes,
  comments, formulas, charts, pivot tables, conditional formatting, slide layouts,
  transitions and more. This is the full ONLYOFFICE editor, not a simplified viewer.
- **Open files from anywhere**: the in-app picker (phone storage, SD card, Google
  Drive and any other document provider), "Open with" from file managers,
  e-mail and chat apps, or "Share" to Office Portable.
- **Save** overwrites the original file when Android allows it. Old binary formats
  (`.doc`, `.xls`, `.ppt`) and read-only sources are saved as a new modern file
  (`.docx`, `.xlsx`, `.pptx`) in a location you choose.
- **Create** blank documents, spreadsheets and presentations.
- **Export to PDF** and **Share a copy**.
- Asks before closing a document with unsaved changes.
- Works fully offline, with no account and no uploads. Documents never leave the device.

On phones the editor uses ONLYOFFICE's desktop toolbar, adapted for narrow screens.
It is roomiest on tablets or a phone in landscape.

## Large files and low-memory phones

The editor engine is heavy. Even an empty document uses about 370 MB inside the
WebView, and a 78 MB photo slideshow used to peak at about 1.6 GB while saving.
The app reduces that in three ways:

- **Lighter copy for photo-heavy files.** When a Word, Excel or PowerPoint file has
  large photos (over 4 MB of them on phones with 3 GB of RAM or less, over 10 MB
  otherwise), the app offers to open a copy with the photos resized to screen size
  (1600 px on low-RAM phones, 2048 px otherwise). Layout is untouched, and only the
  picture bytes change. Saving over the original asks for confirmation first.
  Measured on a 16-photo, 78 MB presentation:

  | | File | Open peak | Save peak | Save time |
  |---|---|---|---|---|
  | Original | 78 MB | 1316 MB | 1652 MB | 6.8 s |
  | Lighter copy, 2048 px | 10.6 MB | 703 MB | 815 MB | 1.4 s |
  | Lighter copy, 1600 px | 5.7 MB | 655 MB | 695 MB | 1.1 s |

- **The file converter is shut down right after use.** It needs 300 MB or more while
  it runs, and upstream keeps it for 30 s after every open and save. The app shuts it
  down after 1.5 s on low-RAM phones (4 s otherwise). It restarts in under a second
  when you next save.
- **One editor at a time on low-RAM phones.** All open editors share one WebView
  process, so opening a file closes other editors that have no unsaved changes.

## Known limitations

- **Spell check is off.** The upstream bundle disables it because its engine could
  hang document loading.
- **Phones get a dense toolbar.** Every editing feature is there, but buttons are
  small. Landscape or a tablet works best.
- **Macros** in `.docm`/`.xlsm` files are kept in neither the editor nor saved copies;
  those files are saved as `.docx`/`.xlsx`.
- **Some `.odp` files** fail to open with "Document conversion failed". This is a
  converter limitation. Other `.odp` files open and save normally.
- **Very large files** (hundreds of pages, or spreadsheets with hundreds of thousands
  of cells) can still exhaust the memory of low-end phones. The app then closes the
  editor and tells you.
- Unsaved edits are lost if Android kills the app in the background, so save before
  switching away for long.

## How it works

```
EditorActivity (Java)
 └─ WebView  https://appassets.androidplatform.net/  (served from the APK, offline)
     └─ /__android/host.html      app ⇄ editor bridge (open bytes, save bytes, dirty flag)
         └─ /editor?embed=1       ONLYOFFICE sdkjs + web-apps, x2t converter in WebAssembly
```

- `AssetServer` serves the bundled editor from the APK's assets and streams the
  opened document straight from Android's `ContentResolver`.
- The editor exports the edited file and the host page streams it back to Java in
  chunks. Java writes it to the original `Uri` or to a `Save as` location.
- The editor bundle comes from [ranuts/document](https://github.com/ranuts/document),
  which packages ONLYOFFICE's [sdkjs](https://github.com/ONLYOFFICE/sdkjs) and
  [web-apps](https://github.com/ONLYOFFICE/web-apps) with the
  [x2t WebAssembly converter](https://github.com/cryptpad/onlyoffice-x2t-wasm).
  The exact version is pinned in `scripts/EDITOR_REF`.
- `scripts/fill-font-faces.py` patches the bundle's font registry so substitute
  fonts (Calibri → Carlito, ...) have bold and italic faces. Without it, bold body
  text and Word headings render as garbled, shifted glyphs.

## Build

GitHub Actions builds every push to `main` (`.github/workflows/build.yml`) and
publishes the APK as a release.

To build locally you need Node.js 24, pnpm 11, Python 3, JDK 17 and the Android SDK:

```bash
./scripts/build-editor.sh               # builds the editor into android/app/src/main/assets/www
gradle -p android assembleRelease       # or open android/ in Android Studio
```

### Signing (so updates install over the previous version)

Without a signing key, each build is signed with a throwaway key, so you must
uninstall the old version before you install a new one. To sign every build with
the same key, create one once:

```bash
keytool -genkeypair -keystore release.jks -storetype PKCS12 -alias officeportable \
  -keyalg RSA -keysize 3072 -validity 18250 -dname "CN=Office Portable"
base64 -w0 release.jks     # copy the output
```

Then, under **Settings → Secrets and variables → Actions** in this repo, add these secrets:
`OP_KEYSTORE_B64` (the base64 text), `OP_KEYSTORE_PASSWORD`, `OP_KEY_PASSWORD`
and `OP_KEY_ALIAS` (`officeportable`). Keep `release.jks` safe: losing it means
users must reinstall the app to update.

## License

AGPL-3.0, because the bundled ONLYOFFICE editors and ranuts/document are AGPL-3.0.
If you distribute this app, you must make its source code available, for example
by linking to this repository. ONLYOFFICE is a trademark of Ascensio System SIA. This
project is not affiliated with or endorsed by them.
