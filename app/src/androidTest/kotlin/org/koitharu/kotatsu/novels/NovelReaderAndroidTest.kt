package org.koitharu.kotatsu.novels

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.tabs.TabLayout
import com.google.android.material.bottomnavigation.BottomNavigationView
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Rule
import org.junit.Before
import org.junit.Assert.assertEquals
import org.koitharu.kotatsu.R
import java.io.File
import android.graphics.Bitmap
import org.jsoup.Jsoup
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on Android's regex engine, which rejects a bare closing brace unlike the host JVM. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class NovelReaderAndroidTest {
    @get:Rule val hiltRule = HiltAndroidRule(this)
    @Before fun setUp() { hiltRule.inject() }

    @Test fun novelNavigationIsVisibleAndFavoritesOpens() {
        ActivityScenario.launch(NovelsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val modes = activity.findViewById<TabLayout>(R.id.reading_modes)
                assertEquals(2, modes.tabCount)
                assertEquals(1, modes.selectedTabPosition)
                assertTrue(modes.isShown)
                assertTrue(activity.findViewById<BottomNavigationView>(R.id.novel_nav).isShown)
            }
            screenshot("novels-explore.png")
            scenario.onActivity { activity ->
                activity.findViewById<BottomNavigationView>(R.id.novel_nav).selectedItemId = R.id.novel_favorites
            }
            screenshot("novels-favorites.png")
        }
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), name)
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun chapterStylesAreParsedOnAndroid() {
        val doc = Jsoup.parse("""
            <style>.hidden { display: none; } .visible { color: red; }</style>
            <div class="chapter-content"><p class="visible">A readable chapter.</p>
            <p class="hidden">Hidden text.</p><p style="visibility: hidden">Hidden inline.</p></div>
        """)
        val html = RoyalRoadRepository.parseChapter(doc)
        assertTrue(html.contains("A readable chapter."))
        assertFalse(html.contains("Hidden"))
    }
}
