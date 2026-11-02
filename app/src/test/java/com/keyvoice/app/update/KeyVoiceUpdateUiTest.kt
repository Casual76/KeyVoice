package com.keyvoice.app.update

import dev.antigravity.fluidengine.foundation.AvailableAppUpdate
import dev.antigravity.fluidengine.ui.fluid.FluidUpdateRelease
import dev.antigravity.fluidengine.ui.fluid.FluidUpdateUiState
import dev.antigravity.fluidengine.ui.fluid.isBusy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La traduzione fra l'esito di un controllo e lo stato che la dashboard disegna.
 *
 * Sono i casi che prima stavano in `MainSetupActivity` come quattro campi e tre `if`, e che nessun
 * test copriva: la dashboard non ha test di interfaccia, quindi la regola e' stata portata fuori in
 * funzioni pure e provata qui.
 */
class KeyVoiceUpdateUiTest {

    private val update = AvailableAppUpdate(
        version = "1.3.0",
        changelog = "Passa al Fluid Engine 3.0.",
        releaseTag = "stable-keyvoice-v1.3.0",
        apkAsset = "keyvoice-1.3.0.apk",
        downloadUrl = "https://example.invalid/keyvoice-1.3.0.apk",
        sizeBytes = 7_340_032L,
    )

    @Test
    fun aFoundUpdateIsAvailableWithItsVersionNotesAndSize() {
        val expected = FluidUpdateUiState.Available(
            FluidUpdateRelease(
                version = "1.3.0",
                notes = "Passa al Fluid Engine 3.0.",
                sizeBytes = 7_340_032L,
            )
        )
        // La novita' c'e' sia che l'abbia chiesta la persona sia che sia partito il controllo da solo.
        assertEquals(expected, updateStateAfterCheck(update, manual = true))
        assertEquals(expected, updateStateAfterCheck(update, manual = false))
    }

    @Test
    fun noUpdateSaysUpToDateOnlyWhenThePersonAsked() {
        assertEquals(FluidUpdateUiState.UpToDate, updateStateAfterCheck(null, manual = true))
        // Il controllo automatico alla riapertura non deve dire niente: la sezione resta com'era.
        assertEquals(FluidUpdateUiState.Idle, updateStateAfterCheck(null, manual = false))
    }

    @Test
    fun aFailedCheckKeepsItsMessageAndIsNotBusy() {
        val state = updateStateAfterFailedCheck("Controllo non riuscito.")

        assertEquals(FluidUpdateUiState.CheckFailed("Controllo non riuscito."), state)
        // Si ritenta con un tocco: se risultasse "in corso" il tasto che lo ripete resterebbe spento.
        assertFalse(state.isBusy)
    }

    @Test
    fun checkingAndAvailableAreDifferentForTheGuardThatProtectsAnInstall() {
        // `checkForUpdates` e `startUpdateInstall` non partono se qualcosa e' in corso: un controllo
        // lo e', una novita' ferma in attesa di un tocco no.
        assertTrue(FluidUpdateUiState.Checking.isBusy)
        assertFalse(updateStateAfterCheck(update, manual = true).isBusy)
        assertFalse(FluidUpdateUiState.UpToDate.isBusy)
    }
}
