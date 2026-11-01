package com.droiddeck.launcher.session

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SteamLanguagePrefsTest {
    @Before fun clearSettings() {
        RuntimeEnvironment.getApplication().getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun unsetSteamLanguageFollowsTheLauncher() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals("", SessionPrefs.steamLanguage(context))
        assertTrue(SteamLanguage.selected(context) in SteamLanguage.all)
    }

    @Test fun explicitSteamLanguageWinsOverTheLauncher() {
        val context = RuntimeEnvironment.getApplication()
        SessionPrefs.setSteamLanguage(context, "spanish")
        assertEquals("spanish", SessionPrefs.steamLanguage(context))
        assertEquals("spanish", SteamLanguage.selected(context))
    }

    @Test fun unknownSavedSteamLanguageFallsBackToTheLauncher() {
        val context = RuntimeEnvironment.getApplication()
        SessionPrefs.setSteamLanguage(context, "klingon")
        assertTrue(SteamLanguage.selected(context) in SteamLanguage.all)
    }
}
