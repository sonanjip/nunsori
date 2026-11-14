# Privacy

Nunsori (Local Search Anything) has no server, no account, no analytics. Indexing and search run on the device.

## What is stored, and where

All in the app's private storage (removed on uninstall or "Clear data"):

- Room database `lsa.db`: file names, paths, metadata (EXIF date and location of photos), extracted text (OCR, document text, transcripts) and embedding vectors.
- Thumbnails (`ThumbStore`, 360 px) of images, document pages and text cards.
- No models: since 0.8.0 they are downloaded and kept by the companion app OndolSaem (see below).
- No chats: since 0.10.0 the chat is the companion app Malbotdari (upgrading deletes the chats, tasks and cards stored by earlier versions, and the photos attached to them).
- Faces found in your photos (face grouping, on by default): for each face its position in the photo, a 128-number identity vector and a small crop, plus the groups and the names you give them. These are **biometric data**. They are computed by OndolSaem on the phone (the photo is handed over and nothing is kept there), stored only in Nunsori's private database and never leave the phone. Turning *Group photos by person* off (Settings › Index) deletes all of them at once.
- The keywords you add to files yourself (*My keywords*), kept per file in the same database until you remove them or the file is deleted.
- Settings (DataStore), including which other apps you allowed to use the index.

Your original files are only read, never modified.

## Network

Since 0.8.0 Nunsori has **no `INTERNET` permission**: it cannot reach the network at all. Models are downloaded by OndolSaem (from Hugging Face and GitHub, as you ask there).

## The OndolSaem model app

The AI models run in the separate app OndolSaem on the same phone. Nunsori sends it what the models need, over Android's on-device Binder IPC: search queries, file contents to embed (photos, text chunks, audio clips, video frames), images for text recognition, recorded audio for transcription, and chat messages with tool results. OndolSaem processes them in memory and returns vectors, text and answers; it does not store them and has no account or analytics. It accepts calls only from apps signed with the same developer key (or an allowed one). OndolSaem's own privacy notes are in its repository.

Place names for photo locations come from a small dataset bundled in the app (GeoNames, offline); coordinates are never sent anywhere.

## Other apps (Connected apps)

Nunsori offers its index to other apps on the same phone over Binder (see [architecture.md](architecture.md#11-index-api-other-apps)). Apps signed with the same developer key (the assistant app Malbotdari) connect directly; any other app is refused until you allow it in **Settings › App › Connected apps**, where you can also deny or remove it later. A connected app can search, read the indexed text of files, get thumbnails, learn which files are new and see the names you gave to people. It cannot read files themselves (opening one goes through Nunsori to the viewer you choose), and moving or deleting files only happens after you approve a card in that app. Nothing leaves the phone: the other app is bound by its own permissions (Malbotdari has no internet access either).

## Permissions (`AndroidManifest.xml`)

| Permission | Why |
|---|---|
| `READ_MEDIA_IMAGES/AUDIO/VIDEO` (`READ_EXTERNAL_STORAGE` up to API 32) | Index photos, audio, video |
| `ACCESS_MEDIA_LOCATION` | Read photo location from EXIF |
| `MANAGE_EXTERNAL_STORAGE` (All files access) | Read text files and documents, which MediaStore does not list reliably; optional, only needed for those kinds |
| `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | Progress notification and long indexing runs; a notification when another app asks to use the index |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Ask (never silently) to leave battery optimization so automatic indexing is not delayed; Google Play restricts this permission, which matters only if the app is ever published there |

## Backup

Android Auto Backup is disabled, so the index, thumbnails and settings are not copied to Google Drive or restored on a new phone.

## Deleting your data

Remove a folder to drop its items, turn face grouping off to delete all face data, or clear app data / uninstall to delete everything. Models are deleted in OndolSaem.

## File actions for other apps

Moving or deleting files happens only after you approve a card in the app that asked (Malbotdari), and only for files that are in the index: deleting goes to the system trash or to the hidden folder `.LSA_Trash` unless you choose *Delete permanently* there. They use the existing "All files access"; no new permission is requested.
