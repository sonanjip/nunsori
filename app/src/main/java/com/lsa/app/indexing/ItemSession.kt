package com.lsa.app.indexing

import android.content.Context
import com.lsa.app.data.db.ItemKind
import com.lsa.app.data.media.SourceEntry
import com.lsa.app.indexing.audio.AudioExtractor
import com.lsa.app.indexing.audio.SpeechTools
import com.lsa.app.indexing.docs.OfficeExtractor
import com.lsa.app.ml.Lease
import com.lsa.app.indexing.docs.PdfExtractor
import com.lsa.app.indexing.docs.ThumbStore
import com.lsa.app.indexing.text.TextExtractor
import com.lsa.app.indexing.video.VideoDetail
import com.lsa.app.indexing.video.VideoExtractor
import com.lsa.app.ml.embedding.Embedder
import com.lsa.app.ml.ocr.OcrEngine

data class VideoOptions(val detail: VideoDetail, val transcribe: Boolean)

/** Dispatches a file to the extractor for its kind. */
class ItemSession(
    private val context: Context,
    private val embedder: Embedder,
    private val ocr: OcrEngine,
    private val thumbs: ThumbStore,
    private val speech: suspend () -> Lease<SpeechTools>,
    private val scratchDir: java.io.File,
    private val video: VideoOptions = VideoOptions(VideoDetail.MEDIUM, true),
) : IndexSession {
    override val embeddingModelId: String get() = embedder.modelId

    override suspend fun embedKeyword(text: String, title: String?): FloatArray = embedder.embedDocument(text, title)

    override suspend fun process(entry: SourceEntry, resume: Long, sink: ItemSink) {
        when (entry.kind) {
            ItemKind.IMAGE -> ImageExtractor(context, embedder, ocr).extract(entry, sink)
            ItemKind.TEXT -> TextExtractor(embedder).extract(entry, resume, sink)
            ItemKind.DOC ->
                if (entry.displayName.endsWith(".pdf", ignoreCase = true)) PdfExtractor(context, embedder, ocr, thumbs).extract(entry, resume, sink)
                else OfficeExtractor(embedder, thumbs).extract(entry, resume, sink)
            ItemKind.AUDIO -> speech().use { AudioExtractor(context, embedder, it.value, scratchDir).extract(entry, resume, sink) }
            ItemKind.VIDEO -> VideoExtractor(context, embedder, thumbs, video.detail, video.transcribe, speech, scratchDir).extract(entry, resume, sink)
            else -> throw SkipItem("unsupported kind ${entry.kind}")
        }
    }
}
