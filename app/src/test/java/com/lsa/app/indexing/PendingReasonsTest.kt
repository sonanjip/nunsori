package com.lsa.app.indexing

import com.lsa.app.data.db.ItemKind
import com.lsa.app.data.db.ItemState
import com.lsa.app.data.db.ItemStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class PendingReasonsTest {
    private fun state(status: ItemStatus = ItemStatus.DONE, mod: Long = 5, key: String = "k") =
        ItemState(1, "ms:image:1", ItemKind.IMAGE, mod, 10, status, key, 0, "Pictures/", "a.jpg")

    @Test fun reasons() {
        assertEquals(PendingReason.NEW, PendingReasons.of(5, 10, null, "k"))
        assertEquals(PendingReason.FAILED, PendingReasons.of(5, 10, state(ItemStatus.FAILED), "k"))
        assertEquals(PendingReason.CHANGED, PendingReasons.of(5, 10, state(mod = 4), "k"))
        assertEquals(PendingReason.RESUME, PendingReasons.of(5, 10, state(ItemStatus.IN_PROGRESS), "k"))
        assertEquals(PendingReason.MODEL, PendingReasons.of(5, 10, state(key = "old"), "k"))
    }
}
