# KeyVoice · verifica redesign Fluid

Questa lista accompagna la migrazione della dashboard a Fluid Engine 2.7.1. Una build riuscita non sostituisce le prove dei servizi IME e accessibilita su telefono.

## Integrazione e build

- [x] Tag remoto piu recente riverificato il 27/09/2026 e submodule agganciato a engine-2.7.1.
- [x] `engine-doctor.ps1`: pin, moduli, tag e versione interna coerenti.
- [x] `:app:assembleDebug` e `:app:testDebugUnitTest`.
- [x] `:app:assembleRelease` e `:app:testReleaseUnitTest`.
- [x] Firma dell'APK release verificata con apksigner.
- [x] Android Lint sul tree corrente: 0 errori, 146 warning; allocazioni durante il disegno dell'overlay, click inaccessibile, Autofill dell'anteprima e regole di trasferimento dati corretti.
- [x] La chiave Groq non viene piu scritta in SharedPreferences in chiaro se la cifratura fallisce; la UI segnala il fallimento e conserva l'input. Eventuale vecchio fallback viene migrato e poi rimosso, oppure rimosso se la migrazione fallisce.
- [x] Release firmata 1.2.15 installata sul Samsung sopra 1.2.14, avviata senza perdita dello stato della chiave; Gboard resta la tastiera predefinita.

## Dashboard su emulatore Android

- [ ] Rivedere l'aspetto dello slider della durata: l'utente lo ha segnalato come non corretto e ha scelto di procedere con la pubblicazione 1.2.15.
- [x] Installazione sopra la build esistente, avvio della dashboard e log senza crash.
- [x] Titolo, stato iniziale, connessione, trascrizione e correzione leggibili sullo schermo stretto.
- [x] Selettore lingua aperto come sheet Fluid.
- [x] Scorrimento fino ad Aggiornamenti e al pulsante Salva, entrambi raggiungibili.
- [x] Selezione modello e cambio preset in bozza; editor prompt personalizzato limitato a 280 dp, modificabile, con Ripristina Default e sezioni successive raggiungibili.
- [x] Modello, preset e modifica del prompt non salvati tornano ai valori persistenti dopo il riavvio.
- [x] Salvataggio reale di modello Whisper turbo, preset Personalizzato e prompt con marcatore temporaneo: tutti persistono dopo il riavvio; modello v3, preset Pulito e prompt originale ripristinati e ricontrollati dopo un secondo riavvio.
- [x] Vocabolario lungo sull'emulatore API 35: 15 termini fittizi in bozza, anteprima limitata a 12, foglio scorrevole fino all'ultimo termine e pulsante Rimuovi esplicito; conteggio aggiornato dopo la rimozione.
- [x] Termini di vocabolario non salvati scartati al riavvio. Il Salva globale include un termine ancora nell'input; persistenza verificata dopo riavvio e termine di prova poi rimosso e salvato.
- [x] Interruttore della correzione esposto nella gerarchia come un'unica riga selezionabile con stato checked e testo descrittivo.
- [ ] Stato chiave assente, chiave valida, chiave non valida e catalogo Groq offline.
- [x] Samsung SM-S931B: con autorizzazione esplicita dell'utente per la sola chiave, `Verifica API Key` ha letto il catalogo Groq reale e mostrato `Elenco aggiornato da Groq`; modelli Whisper e LLM visibili. Nessun audio inviato.
- [x] Tema scuro e testo al 130% sull'emulatore API 35; impostazioni ripristinate dopo la prova.
- [x] Testo al 200% sull'emulatore: righe iniziali leggibili senza badge laterali; impostazione ripristinata.
- [x] Landscape sull'emulatore API 35: titolo, hero e gruppo di setup leggibili; rotazione ripristinata.
- [x] Tablet simulato a 1600x2560/320 dpi con testo al 150%: sezioni iniziali, controlli finali, Aggiornamenti e Salva leggibili e raggiungibili; display e testo ripristinati.
- [x] Samsung SM-S931B (Android 16): release firmata con lo stesso certificato dell'app installata, installazione sopra i dati esistenti, dashboard, sheet del modello, vocabolario, scorciatoie, aggiornamenti e Salva controllati visivamente.
- [x] Samsung SM-S931B: IME KeyVoice inattivo leggibile in un campo dell'app; Gboard ripristinato come IME predefinito.
- [ ] TalkBack e navigazione da tastiera sui campi, switch, sheet e azioni.
- [ ] Scala animazioni di sistema a zero: nessuna animazione decorativa persistente.
- [x] Scala animazioni a zero sull'emulatore: apertura del foglio modello e opzioni visibili; le tre scale originali e la rete dell'app ripristinate dopo la prova.

## Servizi e percorsi di dettatura

- [x] IME: stato idle visibile in tema chiaro e scuro con font al 150%; finestra InputMethod attiva, nessun fallback di inflazione nei log. Gboard, tema e font ripristinati.
- [ ] IME: registrazione, trascrizione, errore, anteprima, inserimento e ritorno alla tastiera precedente.
- [x] IME su emulatore API 35: chiave fittizia salvata con rete IPv4/IPv6 dell'UID KeyVoice bloccata; tastiera visibile nel campo Cerca di Android. Il dump standard omette la finestra IME e l'instrumentation la chiude, quindi il percorso registrazione/errore resta da provare. Gboard, regole di rete e pacchetti di prova ripristinati.
- [x] Overlay accessibilita: servizio attivato temporaneamente sull'emulatore, pannello di registrazione visibile sopra un campo di testo, senza crash; servizio e bozza di prova terminati e impostazioni ripristinate.
- [ ] Overlay accessibilita: elaborazione, successo, errore e trascinamento.
- [ ] Overlay fermo: nessun ridisegno continuo; overlay attivo: movimento fluido.
- [x] Bubble: il tap passa da performClick e l'azione e disponibile ai servizi di accessibilita; gradienti creati al cambio stato/dimensione anziche a ogni frame. Build e Lint passano.
- [ ] Ripetere la prova visiva dell'overlay dopo l'ottimizzazione: nell'emulatore API 35 il servizio risulta collegato, ma la navigazione a gesti non espone un pulsante della scorciatoia utilizzabile; accessibilita ripristinata e build di prova rimossa.
- [x] Verifica API Key con chiave fittizia in bozza: impronta SHA-256 delle preferenze cifrate identica prima e dopo; nessun Salva premuto.
- [x] Aggiornamenti con rete negata alla sola app sull'emulatore: errore visibile e riprovabile; proxy e firewall ripristinati.
- [x] Migrazione modelli: test unitari su modelli ritirati, catalogo della chiave salvata, catalogo di una chiave proposta e modelli ancora validi.
- [ ] Migrazione modelli: verifica end-to-end con catalogo Groq reale e modello salvato ritirato.
- [x] Migrazione della vecchia chiave in chiaro: verificata manualmente su Pampa_Tablet API 35 con un valore fittizio e riavvio offline; file precedente eliminato. Due test strumentali isolati verificano inoltre chiave e cronologia fittizie, rimozione del vecchio file e assenza del valore in chiaro nel file cifrato (`OK (2 tests)`). Pacchetti di prova rimossi e AVD arrestato.
- [ ] Aggiornamenti: disponibile, ignora, rimanda, download, verifica, installazione e errore.
- [ ] Prova su dispositivo fisico Android 13+ con registrazione audio reale. La revisione automatica aveva respinto il tap su Verifica API Key; il 27/09/2026 l'utente ha autorizzato l'invio della sola chiave API, e la verifica live del catalogo e riuscita sul Samsung. L'audio non e autorizzato all'invio a Groq.
