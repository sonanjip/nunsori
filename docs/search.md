# Search

Code: `search/SearchEngine.kt`, `search/Fusion.kt`.

## Pipeline

0. Apply the **filters** (file kinds, date range) first: the set of allowed items is resolved in SQL and everything below only sees those items, so the score distributions are those of the filtered set.
1. Embed the query as text (one vector).
2. Score every vector segment by dot product and split the scores into three **signal groups** (`SignalGroup`): VISUAL, TEXT, AUDIO (mapping in [architecture.md](architecture.md#4-data-model)).
3. Per group, apply `Fusion.relative` to get a score in 0..1.
4. Run SQL `LIKE` keyword matching on segment text (up to 2000 candidates, `KEYWORD_CANDIDATES`).
5. Match the query against item **metadata** (place names, file name, title) the same way (`ItemDao.metaCandidates`, up to 2000, `META_CANDIDATES`).
6. Combine, add the agreement bonus, pin exact matches, trim the tail, group by item, and show the best segment's location.

## Matching new files for scheduled tasks

`SearchEngine.matchItems(query, itemIds, kinds)` answers "does this new file match?" for scheduled tasks that wait for files; other apps call it through the index API (`matchItems`, used by Malbotdari's file tasks). It must not use the ranking above, because relative scores always crown something (the least bad file), and an existing better file hides a new good one. Instead, per item:

- **Keywords:** all query words (or the exact phrase) in the file's text, name, title or place.
- **Meaning:** per signal group, the raw similarity of the item's best segment must be at least `Z_MIN` (2.5) standard deviations above the mean similarity of the whole index (restricted to the task's kinds) and above the group's `minTop`. With fewer than `MIN_BASELINE` (20) vectors there is no distribution, so a fixed floor is used instead (visual 0.20, text 0.65, audio 0.55).

A query may be several `|`-separated alternatives (any one can match; at most 6). It is a recall-oriented pre-filter; the calling app's chat model then checks the candidates (in Malbotdari, for small batches, the setting *judge new files* lets it see every new file with text regardless of this pre-filter). The thresholds are starting values to tune with real files.

## Why relative scores

Cosine similarities live in different ranges per space (image to text vs text to text; unrelated OCR text still scores about 0.6). Raw scores are not comparable and rank fusion (RRF) was tried and dropped: it ignores how good the top hit is, so meaningless queries still return confident-looking results.

## `Fusion.relative(sims, margin, minSpread, minTop)`

- Returns nothing if the best score is below `minTop`.
- With at least 4 segments (`MIN_DOCS_FOR_SPREAD`), returns nothing unless `max - median >= minSpread` (the best must stand out from the typical score).
- Otherwise each score becomes `((s - (max - margin)) / margin)` clamped to 0..1: 1.0 at the maximum, 0.0 at `margin` below it.

| Group | margin | minSpread | minTop | weight |
|---|---|---|---|---|
| VISUAL | 0.10 | 0.08 | 0.10 | 1.0 (`W_VISUAL`) |
| TEXT | 0.08 | 0.08 | 0.30 | 0.8 (`W_TEXT`) |
| AUDIO | 0.10 | 0.08 | 0.30 | 0.8 (`W_AUDIO`) |
| Keyword | n/a | n/a | n/a | 1.2 (`W_KEYWORD`) |
| Metadata (place, name, title) | n/a | n/a | n/a | 1.0 (`W_META`) |

## Keywords (`KeywordMatcher`)

Query is lowercased and split on whitespace; single ASCII letters are dropped, single Korean syllables are kept. ASCII tokens must match whole words; other scripts match as substrings. A match is **exact** when every token matches and the whole query occurs as a phrase.

## Filters (`SearchFilter`)

Kinds (UI: Documents = text files + PDF/Office, Images, Audio, Video; none selected = all) and a date range on `COALESCE(dateTaken, modifiedAt)` (photos by when they were taken, everything else by modification time). The UI offers presets (any date, today, last 7 days, last 30 days, this year) and a custom range. With filters but an empty query the result is a **browse list**, newest first (up to `BROWSE_LIMIT` = 100). Keyword candidates are limited to 2000 before the filter is applied, so a very common word can crowd out matches in a narrow filter.

**Person** (`SearchFilter.items`, `search_index` for other apps only): the photos of one named person, from face grouping; combined with the other filters, and a browse list without a query. The threshold that decides which faces are the same person is in [indexing.md](indexing.md#faces).

## Metadata (`MetaMatch`)

`items.place` holds searchable place names derived from the photo's GPS position ([indexing.md](indexing.md#images-imageextractor)): city and country in English and Korean plus aliases such as `UAE`, `Middle East 중동`, `Asia 아시아`. "Dubai", "두바이", "UAE" or "중동" therefore find photos taken there without any semantic match. File name and title count too. Matching reuses `KeywordMatcher` (ASCII words match whole words, Korean matches substrings). The score is 1.0 when all words and the phrase match, else `0.5 + 0.5 × matched/total`. The badge shows "Place" or "Name".

## Agreement bonus

Final score = sum of the weighted signals + `AGREEMENT_BONUS` (0.15) × (number of different signals that matched − 1). Signals are visual, text, audio, keyword and metadata. A photo that is in Dubai **and** looks like a skyline therefore ranks above one that is only in Dubai.

## Ranking rules

- Exact keyword matches and exact metadata matches (all words and the phrase) are pinned above everything and get a score floor of 0.6 (`EXACT_MATCH_FLOOR`); among them the higher score wins.
- Results scoring below 25% of the top score are dropped (`TAIL_FRACTION`).
- Segments are grouped per item; the best segment gives the page (`p.N`) or time (`mm:ss`) shown on the card. Badges (Image, Text, Audio, Keyword, Place, Name) show which signals matched.
- A video transcript hit is narrowed to the overlapping video clip so the shown time is the clip.

## Tuning

Change the constants above, then re-run the real-model E2E ([development.md](development.md#testing)) with queries that should match and queries that should return nothing. Unit tests in `app/src/test` cover `Fusion` and keyword matching.

`search_in_file(item_id, query, limit)` ranks readable passages within one item by raw cosine when the current embedding model matches, falling back to words otherwise. Up to five 500-character excerpts are returned with page/time references; `read_file` reads more. This is independent of whole-index result fusion.
