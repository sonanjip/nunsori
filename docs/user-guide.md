# User guide

![Index](images/index.png) ![Search](images/search.png) ![Settings](images/settings.png)

## First run

A three-step wizard appears on the first launch. Everything in it can be changed later in Settings.

![Step 1](images/onboarding-1.png) ![Step 2](images/onboarding-2.png) ![Step 3](images/onboarding-3.png)

1. **What to index:** the kinds of files and the folders (the permissions they need are requested here).
2. **AI models:** shows whether the companion app OndolSaem is installed and which model it uses for search, text in images, speech and faces, with buttons to install or open it. Models are downloaded and chosen in OndolSaem (embedding 440M recommended, 740M also understands audio). You can skip this; Search, Index and Chat tell you when a model is missing.
3. **Automatic indexing:** immediately, at a set time, when idle and charging, or manually.

## Index

Shows progress ("n / total files"), the file being processed, **Start / Pause**, and a thumbnail grid of indexed files. Indexing continues in the background with a notification, and resumes after the app is killed.

- **Failed files:** a "Failed (n)" chip filters the grid to files that could not be indexed (tap one to see the error); **Retry failed** runs only those files, without resuming the rest of a paused run. Failed files are also retried by every normal run.
- After the files, the same run looks for faces in photos not scanned yet (People); a second line under the status shows *Finding faces: n of m photos* while it works, so the run is not over yet even when the status says it is up to date.

## Search

Type a description ("launch schedule and design approval"), exact words, a place ("Dubai", "두바이", "중동") or a file name. Each result card shows a thumbnail, the file name with the best location (`p.3` for a page, `mm:ss` for audio/video), a snippet, and badges for what matched: Image, Text, Audio, Keyword. Tap a result to open it in another app; share one or several from the result actions. Exact keyword matches come first. Ranking details: [search.md](search.md).

![Filters](images/search-filters.png)

**Filters:** the chips under the search box limit results to Documents, Images, Audio and/or Video, and to a date range (any date, today, last 7/30 days, this year, or pick dates; photos use the date taken). With a filter but no query you get a list of that kind of file, newest first. While a model switch is re-indexing, a banner says how many files are not searchable yet.

Photos with GPS data are found by place name. Results that match both the meaning and the place rank higher.

Tips: describe content in natural language for photos and scenes; use distinctive words for names, codes and numbers; Korean and English both work.

Automatic indexing shows when it last ran and what it did (or why it did nothing). Immediate mode also checks hourly, in case the system delayed the instant trigger; for dependable timing exclude the app from battery optimization (the same card appears under the options).

## People

Faces are found when photos are indexed (Settings › Index › *Group photos by person*, on by default; needs OndolSaem 0.3.0 or newer with its *Faces* model downloaded). The **People** menu lists the groups: one per person, with a face crop and the number of photos. Groups with a single photo are hidden until you switch *Show people with one photo* on.

- Tap a group to see its photos (tap a photo to open it), give it a name and save. Giving a name that another group already has offers to **merge** the two; *Merge* also lets you pick the other group from a list.
- Tap a face in the row of faces to use it as the cover or to say **Not this person**: the face leaves the group and is never put back into it.
- In the chat app Malbotdari, use the names: "show me photos of Mina from last summer", "move all photos of Mina to Pictures/Mina" (a card lists the files; nothing moves before you approve it).
- **Grouping: looser or stricter** (on the People screen and under *Group photos by person* in Settings) sets how alike two faces must be to count as one person (similarity 0.30 to 0.60, default 0.45). Moving the slider shows how many groups it would make; *Apply* groups all faces again at once without scanning the photos. Names, covers and "Not this person" are kept: a name goes to the group with most of its faces. Stricter splits one person into several groups (fix with *Merge*); looser mixes people (fixed face by face).
- Turning face grouping off deletes every face, group and name. Turning it on again scans only the faces of the photos already indexed; the search index is kept.

## Chat

Since 0.10.0 the assistant chat is its own app, **Malbotdari** (말벗다리). It answers from this index: it searches and reads your files, shows their previews, proposes moving or deleting them (Nunsori does it after you approve the card there) and runs tasks when Nunsori indexes a matching file. **Chat** in the menu opens Malbotdari (or says where to get it); in Search, long-press results and choose **Ask in chat** to open a new Malbotdari chat with those files attached, or tap any file to ask from its detail sheet. Updating from an earlier Nunsori removes the chats, scheduled tasks and cards that were stored here.

## Settings

Four tabs: **Index** (what to index, folders, automatic indexing), **Models** (connection to OndolSaem and the model of each category), **App** (connected apps, storage, resource indicator, language) and **About** (app name, version, GitHub link, open-source licenses).

- **What to index:** Images, Text files, Documents, Audio, Video.
- **Folders:** quick-add chips for Camera, Screenshots and Downloads, or pick any folder; choose whether to include subfolders. (Without "All files access" only photos, audio and video in a folder are indexed.)
- **AI models (OndolSaem):** the models run in the companion app OndolSaem: choosing and downloading them, the memory policy and the Hugging Face token are there (**Open OndolSaem**). Nunsori shows the connection and the model of each category; **Check again** after changing something there. When the search model changes in OndolSaem, the files indexed with the old model are marked for re-indexing (Index shows how many; automatic indexing picks them up).
- **Connected apps (App tab):** other apps that asked to use your index (search and read your files; moving or deleting always needs your OK in that app). Allow, deny or remove each one. Apps by the same developer (Malbotdari) connect without asking. A notification tells you when a new app asks.
- **Resource indicator:** a top-bar readout of the connection to OndolSaem, this app's memory and CPU use. The models' memory is on OndolSaem's dashboard.
- **Automatic indexing:** Manual; Immediately (new files are picked up in the background); Scheduled (a time, optionally only while charging); When idle (charging and unused: the screen off for 10, 30 or 60 minutes when the battery is at 90 % or more, 70–89 % or below 70 %; *Sooner* and *Later* shorten or lengthen these; stops when you use the phone).
- **Model memory:** keep loaded, unload after 2 minutes idle (default), or unload immediately. Lower memory use costs a reload on the next use.
- Dark mode follows the system.

## Why the permissions

See [privacy.md](privacy.md#permissions-androidmanifestxml).

## Troubleshooting

| Symptom | Check |
|---|---|
| Nothing found | Is the file `DONE` on the Index screen? Is the folder added and the kind enabled? Text/documents need All files access. |
| Documents or text never appear | Grant "All files access" in system settings. |
| "No search model available" | Install OndolSaem, download a model there, then **Check again** in Settings › Models. |
| "OndolSaem is too old" / "no longer supports this version" | Update the app named in the message (Settings › Models has a button). |
| "OndolSaem refused the connection" | Nunsori and OndolSaem must be signed with the same key (or an allowed one), e.g. both debug builds from the same machine. |
| Slow indexing | Emulators use CPU. On phones, a hot device pauses automatic runs. |
| Audio never matches by sound | Audio embedding needs the 740M model; transcripts need the speech model (both chosen in OndolSaem). |
| Old .doc/.ppt/.xls or hwp | Unsupported; convert to the modern format or PDF. |
