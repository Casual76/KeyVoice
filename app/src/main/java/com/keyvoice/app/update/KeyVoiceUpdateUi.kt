package com.keyvoice.app.update

import dev.antigravity.fluidengine.foundation.AvailableAppUpdate
import dev.antigravity.fluidengine.ui.fluid.FluidUpdateUiState
import dev.antigravity.fluidengine.ui.fluid.toFluidUpdateRelease

/**
 * Come l'esito di un controllo diventa lo stato che la dashboard disegna.
 *
 * Prima la dashboard teneva quattro campi (la novita', lo stato dell'installazione, "sto
 * controllando" e una frase) e `updateStatus()` li ricomponeva in un testo; ora c'e' un solo
 * [FluidUpdateUiState] e la sezione di aggiornamento dell'engine lo legge da sola. Qui sta l'unica
 * regola che e' di KeyVoice: un controllo che non trova niente e' "app aggiornata" solo se l'ha
 * chiesto la persona; quello automatico, che parte da solo alla riapertura, non deve dire niente.
 */
internal fun updateStateAfterCheck(update: AvailableAppUpdate?, manual: Boolean): FluidUpdateUiState = when {
    update != null -> FluidUpdateUiState.Available(update.toFluidUpdateRelease())
    manual -> FluidUpdateUiState.UpToDate
    else -> FluidUpdateUiState.Idle
}

/** Un controllo fallito (rete, manifest): si ritenta con un tocco, non e' un'installazione fallita. */
internal fun updateStateAfterFailedCheck(message: String): FluidUpdateUiState =
    FluidUpdateUiState.CheckFailed(message)
