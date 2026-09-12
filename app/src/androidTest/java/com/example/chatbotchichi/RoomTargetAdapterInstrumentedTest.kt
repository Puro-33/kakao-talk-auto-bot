package com.example.kakaotalkautobot

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RoomTargetAdapterInstrumentedTest {
    private lateinit var context: Context
    private lateinit var testFiles: File
    private lateinit var preferencesName: String

    @Before fun setUp() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val testId = "room-adapter-${UUID.randomUUID()}"
        preferencesName = "$testId-prefs"
        testFiles = File(base.cacheDir, testId).apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = testFiles
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences(preferencesName, mode)
        }
    }

    @After fun tearDown() {
        context.deleteSharedPreferences(preferencesName)
        testFiles.deleteRecursively()
    }

    @Test fun replacementCopiesCallerListAndDispatchesOnlyActualChanges() {
        val room = AppSettings.RoomTarget("테스트방", true, 0L, null)
        val source = mutableListOf(room)
        val adapter = adapter(source)
        val observer = Changes()
        adapter.registerAdapterDataObserver(observer)

        source.clear()
        assertEquals(1, adapter.itemCount)
        adapter.replaceItems(listOf(room))
        assertEquals(0, observer.changed)
        adapter.replaceItems(listOf(room.copy(isEnabled = false)))
        assertEquals(1, observer.changed)
        adapter.replaceItems(emptyList())
        assertEquals(1, observer.removed)
        assertEquals(0, observer.fullRefreshes)
    }

    @Test fun memoryOnlyChangeRefreshesTheExistingRoomRow() {
        val room = AppSettings.RoomTarget("메모 테스트방", true, 0L, null)
        val adapter = adapter(listOf(room))
        val observer = Changes()
        adapter.registerAdapterDataObserver(observer)

        AppSettings.saveRoomMemory(context, room.name, "직접 입력한 메모")
        adapter.replaceItems(listOf(room))
        assertEquals(1, observer.changed)
        assertEquals(0, observer.fullRefreshes)
    }

    private fun adapter(rooms: List<AppSettings.RoomTarget>) = RoomTargetAdapter(
        rooms, context, "설정", {}, {}, {}, { _, _ -> }
    )

    private class Changes : RecyclerView.AdapterDataObserver() {
        var changed = 0
        var removed = 0
        var fullRefreshes = 0
        override fun onChanged() { fullRefreshes++ }
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
            changed += itemCount
        }
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
            removed += itemCount
        }
    }
}
