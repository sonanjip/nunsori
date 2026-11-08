# Indexing

Values below are read from the code; the file is given so you can check them.

## Sources and kinds

| Kind | Source | Extensions / notes |
|---|---|---|
| IMAGE, AUDIO, VIDEO | `MediaStoreRepository` | what MediaStore reports; needs media permissions |
| TEXT | `FileSource` | txt/plain, md (`md, markdown, mdown, mkd`), html (`html, htm, xhtml`), xml family (`xml, svg, rss, atom, plist, xsd, xsl`), `csv`, `tsv`, subtitles (`srt, vtt`); full list in `text/TextRouting.kt` |
| DOC | `FileSource` | PDF, docx/pptx/xlsx (OOXML), odt/odp/ods (ODF). Old .doc/.ppt/.xls and hwp are unsupported |

`FileSource` needs "All files access". Folder keys equal MediaStore `RELATIVE_PATH`. You choose kinds and folders (with or without subfolders) in Settings.

## Text (`indexing/text`)

- Reads up to 25 MiB and 20,000 chunks per file (`TextExtractor`). Encoding is detected in `TextDecoder`.
- `TextChunker` (`ChunkerConfig`): target 800 chars, max 1000, min 200, overlap 120. Splits at blank lines, then lines, then sentences, then spaces, and cuts hard only for unbroken runs.
- `MarkdownChunker` follows headings and keeps the heading as the chunk title; it falls back to plain chunking.
- HTML to text with jsoup; XML keeps text nodes only; CSV/TSV rows are grouped; subtitles keep timing (`startMs/endMs`).

## Images (`ImageExtractor`)

One `IMAGE` segment (embedding at max side 1024) and one `OCR_TEXT` segment (OCR at max side 1600, text capped at 6000 chars for storage and keyword search; only the first 1500 chars are embedded, and a failed text embedding keeps the image vector instead of failing the file). EXIF date and location are stored on the item. SVG files (which the platform `ImageDecoder` rejects) are rendered with AndroidSVG on a white background (`BitmapLoader.decodeSvg`); their thumbnails use Coil's SVG decoder.

**Places.** The EXIF position is turned into searchable names by `PlaceFinder` (`indexing/places`, data `assets/places.bin`, regenerate with `scripts/make-places.py`): the nearest city within 100 km, plus the largest city of 500k+ people within 50 km, each with city and country in English and Korean plus country aliases and regions (e.g. `Dubai 두바이 United Arab Emirates 아랍에미리트 연합 Asia 아시아 UAE Emirates Middle East 중동`; abbreviations, continents and regions come from `ABBREVIATIONS`, `CONTINENTS`, `REGIONS` in `scripts/make-places.py`; ISO codes are avoided because "IN" or "ARE" are ordinary words), stored in `items.place` (`""` = looked up, nothing near). `IndexRunner.sync()` fills `place` for older rows that have a position but no name (`backfillPlaces`, no model needed); when the places data changes, an `AppUpgrade` step calls `ItemDao.resetPlaces()` so every row is looked up again. Videos are not geotagged yet.

## Text length limit

The embedding model accepts 1024 tokens and LiteRT-LM exposes no tokenizer, so length is only known when a call fails ("input sequence length (N) exceeds maximum supported signature length(1024)"). `EmbeddingGemma2Embedder.embedDocument/embedQuery` therefore retry with 60% of the text up to 5 times (`retryShorter`), for every caller (text, PDF, Office, OCR, transcripts, queries). Titles are capped at 200 chars and queries at 1000. Dense Korean or CJK text, long OCR output and tables are the usual causes.

## Failed files

An exception during extraction marks the item `FAILED` with the message in `items.error`. Every run (`sync`) puts failed items back in the queue; the Index screen lists them behind the "Failed" chip. **Retry failed** (`IndexingController.retryFailed`, worker flag `only_failed`) processes just those files and leaves other pending work paused. Skipped items (`SKIPPED`) are unsupported or too large files and are not retried.

## Priority and heat

Indexing requests go to OndolSaem with background priority, so searches (and other apps' chat) go first; OndolSaem picks the backend (GPU or CPU) itself. Automatic runs stop on a thermal status of MODERATE or higher and retry later; manual runs do not. The low-power switch for manual runs (CPU only, pauses between files) was removed in 0.8.1 because the app no longer chooses the backend; `AppUpgrade` drops its stored value.

## PDF (`PdfExtractor`, `PdfText`)

Up to 500 pages. Text comes from pdfbox; a page with fewer than 15 meaningful characters (`PdfText.MIN_CHARS`, punctuation excluded) is treated as scanned and goes through OCR. Each page is rendered (width 1200, max height 2400, embedded at side 1024) as a `PAGE_IMAGE` and its text is chunked as `TEXT_CHUNK` with the page number. Thumbnails are 360 px (`ThumbStore`).

## Office (`OfficeExtractor`, `OfficeParsers`, `TextCard`)

Phones cannot render Office files, so text is extracted (up to 300 chunks per slide/sheet/section) and a generated **text card** image is embedded as `CARD_IMAGE`. Spreadsheets become sheet text with rows kept together.

## Audio (`indexing/audio`)

Decoded to 16 kHz mono PCM (max 4 hours, `AudioDecoder`). Two stages with separate checkpoints:

- **Stage A, transcript:** Silero VAD finds speech spans, SenseVoice transcribes them into `TRANSCRIPT` segments with times.
- **Stage B, audio clips:** only with the 740M model. Windows of about 30 s (`TARGET_MS`) with 5 s overlap, cut at silence within 6 s (`SNAP_MS`), never over 36 s (`MAX_MS`, the model takes about 40 s); a tail shorter than 4 s is merged. Windows that are pure silence (RMS below 0.002) are skipped but progress still advances. Each window is a WAV `AUDIO_CLIP`.

Without an ASR model only stage B runs; without the 740M model only stage A runs; with neither the file is `SKIPPED`.

## Video (`indexing/video`)

Clips are planned from scene changes (threshold 0.10 on a 16×16 grid) with length 4 to 20 s and 20% overlap. Each clip embeds up to 8 frames (side 448) as one `VIDEO_CLIP` vector. If the video has speech, stage "audio" (`AUDIO_STAGE`) transcribes it; transcript segments are about 220 chars or 10 s. Precision is a Settings option and part of `pipelineKey`. Moments shorter than a few seconds can be missed.

## Automatic runs: how they are kept alive

Immediate mode watches `MediaStore.Files` with a one-shot WorkManager content trigger (15 s quiet time, 2 min at most, battery not low). To avoid a blind window, a trigger run **arms the next trigger when it starts**, using two alternating unique-work slots (`auto-trigger-a`/`-b`, `AutoIndexScheduler.armNextTrigger`; `KEEP` leaves a waiting slot alone). A run also loops (at most 3 rounds, never re-trying failed files) while new files arrive during it. Automatic runs never end in WorkManager "failure": an unexpected exception, thermal throttling or a model-load failure return `retry()` with a linear 2-minute backoff (not the default exponential up to 5 hours). Immediate mode also queues an hourly catch-up (`auto-immediate-net`, battery not low) in case the system deferred or never delivered a trigger; it also catches plain files that never touch MediaStore (documents copied without a media scan). An unloaded model is not a reason to skip: the worker loads it.

`SettingsStore.lastAutoRun` records each automatic run (time, source, outcome: indexed N, nothing new, too warm, model missing/failed, no access, no folders, error); Settings › Automatic indexing and the Index screen show it. The system can still delay jobs of battery-optimized apps (Doze, standby buckets, Samsung sleeping apps), so the auto-index options show the battery-optimization card.

## Faces

After the files of a run are processed (and also when there was nothing new), `IndexingWorker` runs the **face pass** (`indexing/faces/FaceIndexer`) when *Group photos by person* is on (`faceGrouping`, default on) and OndolSaem can find faces (`ModelHost.faces()`: OndolSaem API 2 and its `face` model downloaded; the mirrored id is `faceModelId`).

- Photos to scan: `IMAGE` items that are `DONE` and whose `faceKey` differs from `<face model id>|f<FaceIndexer.FACE_KEY_VERSION>`. A photo is decoded at max side 1600, sent as JPEG (background priority) and its faces stored with a 160 px crop (`ThumbStore`, kept by the thumbnail cleanup). A photo that is processed again replaces its faces; a photo that fails is marked scanned and skipped until the face model changes.
- The pass never touches `pipelineKey` or the search segments, so turning face grouping on again (or a new face model) only scans faces. A changed face model deletes all faces and groups first, because vectors of two models cannot be compared.
- The same gate as indexing stops it (stop button; heat during automatic runs); the next run continues where it stopped. Progress is shown on the People screen (`FaceIndexer.progress`).
- **Grouping** (`FaceIndexer.regroup`, `FaceClusterer.match`/`merges`): every face without a group joins the group whose centroid (normalized mean of its faces) is most similar, if the cosine is at least the **grouping threshold** (`faceThreshold`, default `FaceClusterer.DEFAULT_THRESHOLD` = 0.45, range 0.30 to 0.60; OpenCV recommends 0.363 for SFace, which separated the emulator fixtures, same person 0.54 to 0.90 and different people at most 0.22, but mixed people on real libraries), except the group it was taken out of; otherwise it starts a new group. Then groups whose centroids reach the threshold are merged, the most similar pair first: a named group absorbs an unnamed one, two named groups are never merged, and a group is never merged with a group one of its faces was taken out of. Groups left without faces are deleted.
- **Grouping everything again** (`FaceIndexer.regroupAll`, `FaceClusterer.regroupAll`): a new threshold (Settings › Index or People › *Grouping: looser or stricter*) or a new grouping method needs no scan: all faces are grouped again from the stored vectors, best faces (`score`) first, then merged as above. What the user did is kept: faces of two differently named groups never end up together, a face taken out of a group never joins that group's faces again (its block now points at the group that stands for it), and each old group hands its id, name and cover to the new group holding most of its faces (a cover that left its group is reset). The dialog previews the number of groups (`previewGroups`, nothing written). The settings remember what the groups were made with (`faceGroupingKey` = `g<GROUPING_VERSION>|t<threshold>`); a different key (an install from before 0.11.0 has none, and 0.11.0 raised the default from 0.363 to 0.45) groups everything again once, at app start and before a face pass.
- Turning face grouping off (Settings) deletes faces, groups, crops and every `faceKey`.

## Checkpoints and re-processing

- `items.progress` records where to resume (segment ordinal, or audio stage offsets such as `STAGE_B = 10^12`).
- Commits are per file; killing the app loses at most the current batch.
- `pipelineKey` (`PipelineKeys.kt`) mixes model id, chunker version, video precision, etc. Changing it reprocesses only that kind.

## Sync (`IndexRunner.sync`)

Deleted files are pruned, moved files updated in place, changed files (size or modified time) reprocessed. An empty listing is treated as an error condition and never wipes the index. Sync does not need any model.

## After a pass: scheduled file tasks

When a pass indexed files, `IndexingWorker` calls `IndexEvents.notifyChanged`: apps that listen (Malbotdari's scheduled file tasks) then ask `changesSince` for the items that reached `DONE` after their cursor (`indexedAt`), see [architecture.md](architecture.md#11-index-api-other-apps). Such tasks therefore fire after indexing, not when a file is created.

ODS repeated empty cells retain their column positions, so subsequent values remain under the correct headers (within the column limit). Reindex an existing ODS file to refresh text extracted by an older version.
