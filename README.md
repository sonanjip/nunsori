# Nunsori (눈소리)

[한국어](README.ko.md)

*Local Search Anything.* **N**ative **U**nified **N**atural-language **S**earch, **O**ffline **R**ecall & **I**ndex. The name is Korean for "eye" (눈) and "sound" (소리): it sees and hears your files.

An Android app that indexes the files on your phone with on-device multimodal embedding models and lets you search them by meaning and by keyword. Nothing leaves the device. The AI models run in the companion app [OndolSaem / 온돌샘](https://github.com/sonanjip/ondolsaem), which downloads them once and shares them with other apps.

<p>
<img src="docs/images/demo-search.gif" width="260" alt="Files found by meaning in English and Korean ('italian food' finds pizza photos, '회의록' finds the meeting-notes images by their text)">
</p>

*Files on the phone found by meaning and by the words in them, in English and Korean.*

The assistant chat that answers from these files is the companion app [Malbotdari / 말벗다리](https://github.com/sonanjip/malbotdari) (since 0.10.0): it uses this index through Nunsori's SDK. Notes (memos, saved pages and videos, recordings with summaries) live in the companion app [Jogakbo / 조각보](https://github.com/sonanjip/jogakbo), which Malbotdari reads too.

## Features

| Kind | What is indexed | Searchable by |
|---|---|---|
| Images | Image embedding + OCR text (ML Kit, Korean and Latin) | Description, text in the image |
| Text files | txt, md, html, xml, csv/tsv, srt/vtt in overlapping chunks | Meaning, keywords |
| Documents | PDF pages (text, OCR fallback, page image); docx/pptx/xlsx and ODF as text + text card image | Meaning, keywords, page |
| Audio | Speech transcript (SenseVoice); audio clip embeddings (740M model) | Spoken words, sound, time |
| Video | Scene clips (up to 8 frames each) + speech transcript | Visual content, speech, time |
| People | Faces in photos (detected and recognized by OndolSaem), grouped by person on the phone | People menu (name, merge, "not this person", grouping looser or stricter); in Malbotdari: "move all photos of Mina to Pictures/Mina" |

Also: an index other apps can use with your permission (search, read, move/trash after your approval there; Settings › App › Connected apps), "Ask in chat" from search results or file details (opens Malbotdari with the files attached), a failed-file list with retry, an optional resource indicator (GPU/CPU, memory), hybrid ranking (meaning + exact words + photo place names such as "Dubai"), filters by file kind and date, share/open results, automatic indexing (manual, immediate, scheduled, idle: while charging and unused for 10 to 60 minutes depending on the battery), English and Korean UI, dark mode. Choosing models and how long they stay in memory happens in OndolSaem.

## How it works

Files become *segments* (a chunk, a page, a clip) with an int8 embedding, stored in Room. A query is embedded, compared with all segments per signal group (visual, text, audio), combined with SQL keyword matches, and grouped per file with the best page or time. See [architecture](docs/architecture.md).

## Requirements

Android 10+ (minSdk 29) and the **OndolSaem** app (models are downloaded there):  EmbeddingGemma 2 440M (388 MB) or 740M (adds audio), SenseVoice (163 MB) for speech, and the face model for People. For the chat: Malbotdari with a chat model in OndolSaem. Text and documents need "All files access".

## Quick start

```bash
source scripts/env.sh && ./gradlew assembleDebug && scripts/emu-start.sh && scripts/run.sh
# plus OndolSaem (separate repository): build and install it, then download models in it
```

Then in the app: the first-run wizard asks what to index (kinds, folders), checks the connection to OndolSaem and when to index automatically; then press Start indexing and search. Details: [getting-started](docs/getting-started.md).

## Privacy

Everything runs on the device; Nunsori has no network permission. Models are downloaded by OndolSaem. See [privacy](docs/privacy.md).

## Limits

Office files are not rendered (text and a generated card only); old .doc/.ppt/.xls and hwp are unsupported; the in-memory vector scan targets about 300k segments; short video moments can be missed. See [roadmap](docs/roadmap.md).

## Documentation

[docs/](docs/README.md) · [Contributing](CONTRIBUTING.md) · [Third-party notices](THIRD_PARTY_NOTICES.md)

## About the name

**눈소리 (Nunsori)** joins two Korean nouns: **눈** (*nun*, eye) and **소리** (*sori*, sound). The app embeds what can be seen (photos, document pages, video frames, text in images) and what can be heard (speech and audio) into one index, so its name says both: it sees and hears your files. Like its sibling apps it is a pair of everyday Korean words that is easy to say in English ("noon-so-ree").

The backronym spells out what it does: **N**ative (on the phone only), **U**nified (images, documents, audio and video together), **N**atural-language **S**earch, **O**ffline, **R**ecall & **I**ndex.

## License

[Apache-2.0](LICENSE). Copyright 2026 Taehun Kim. Models keep their own licenses (see OndolSaem).
