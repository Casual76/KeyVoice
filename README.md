# KeyVoice

**Dettatura rapida Android con trascrizione intelligente**

KeyVoice trasforma la voce in testo usando le API Groq. La modalita principale usa la scorciatoia accessibilita di Android 13+ per dettare nel campo attivo senza cambiare tastiera; la tastiera personalizzata (IME) resta disponibile come percorso secondario.

## Funzionalita'

- **Scorciatoia accessibilita**: dettatura rapida nel campo attivo con pannello flottante.
- **Tastiera vocale secondaria**: interfaccia minimale centrata sul microfono.
- **Trascrizione con Whisper**: supporto a `whisper-large-v3` e `whisper-large-v3-turbo`.
- **Correzione automatica**: punteggiatura, grammatica ed errori di trascrizione corretti da LLM.
- **Funziona ovunque**: WhatsApp, Chrome, Note, email e qualsiasi app con input testuale.
- **Tema chiaro/scuro**: si adatta automaticamente al tema di sistema.
- **Indicatore audio**: visualizzatore del livello audio in tempo reale durante la registrazione.
- **Annulla rapido**: pulsante per rimuovere l'ultimo testo inserito.
- **Cambio tastiera**: pulsante dedicato per tornare rapidamente a un'altra tastiera.
- **Verifica API Key**: controllo rapido della chiave Groq dalla dashboard.

## Requisiti

- Android 13+ (API 33+)
- Connessione Internet
- Chiave API Groq

## Ottenere una API Key Groq

1. Vai su [console.groq.com](https://console.groq.com).
2. Crea un account o accedi.
3. Apri la sezione **API Keys**.
4. Crea una nuova chiave.
5. Copia la chiave nella dashboard KeyVoice.

## Installazione

### Da Android Studio

1. Clona il repository:

   ```bash
   git clone https://github.com/your-username/KeyVoice.git
   ```

2. Apri il progetto in Android Studio.
3. Sincronizza Gradle e compila il progetto.
4. Installa l'APK sul dispositivo:

   ```bash
   ./gradlew installDebug
   ```

### Primo avvio

1. Apri **KeyVoice** dal launcher.
2. Attiva KeyVoice nelle impostazioni accessibilita di sistema.
3. Inserisci la API Key Groq nella dashboard.
4. Usa **Verifica API Key** per controllare che la chiave sia valida.

## Utilizzo

1. Apri qualsiasi app con un campo di testo.
2. Richiama la scorciatoia accessibilita KeyVoice.
3. Detta mentre il pannello flottante e' visibile.
4. Tocca il pannello o richiama di nuovo la scorciatoia per fermare la registrazione.
5. Attendi trascrizione, raffinamento e inserimento automatico nel campo attivo.

### Tastiera secondaria

Se preferisci usare KeyVoice come IME, abilitala nelle impostazioni tastiera di sistema e selezionala da un campo di testo. L'avvio automatico della registrazione e' disattivato di default per non competere con la modalita accessibilita.

### Pulsanti della tastiera

| Pulsante | Posizione | Funzione |
| --- | --- | --- |
| Microfono | Centro | Avvia o ferma la registrazione |
| Tastiera | In basso a sinistra | Passa alla tastiera successiva o apre il selettore |
| Annulla | In basso al centro | Rimuove l'ultimo testo inserito |
| Impostazioni | In basso a destra | Apre la dashboard |

## Impostazioni

| Opzione | Descrizione | Default |
| --- | --- | --- |
| API Key | Chiave API Groq salvata in modo sicuro | Vuota |
| Lingua | Italiano, English, Auto-detect | Italiano |
| Modello Whisper | Modello usato per la trascrizione | `whisper-large-v3` |
| Modello LLM | Modello usato per il raffinamento | `gpt-oss-20b` |
| Fase 2 | Abilita/disabilita correzione LLM | Abilitata |
| Durata max | Durata massima registrazione, 30s-10min | 3 minuti |
| Feedback aptico | Vibrazione al tocco del microfono | Abilitato |
| Prompt di sistema | Istruzioni per la correzione LLM | Prompt KeyVoice |
| Vocabolario | Termini personalizzati per Whisper | Vuoto |
| Apprendimento automatico | Aggiunge termini al vocabolario solo dalle correzioni di testo appena dettato con KeyVoice | Disattivato |

## Dettagli tecnici

### Pipeline

```text
Audio M4A/AAC -> Whisper API -> Testo grezzo -> LLM API opzionale -> Testo raffinato -> InputConnection
```

### Limite di registrazione

La durata massima e' configurabile. Il default e' 3 minuti, sotto il limite tipico di dimensione file per la trascrizione. Negli ultimi 10 secondi appare un countdown e al raggiungimento del limite la registrazione si ferma automaticamente.

### Stack

- Kotlin con Coroutines
- `InputMethodService`
- `MediaRecorder`
- Retrofit + OkHttp
- `EncryptedSharedPreferences`
- Material Design 3 con layout XML

### Architettura

```text
com.keyvoice.app/
|-- MainSetupActivity.kt              Dashboard e impostazioni
|-- ime/
|   |-- VoiceKeyboardService.kt       Servizio IME principale
|-- ui/
|   |-- KeyboardViewController.kt     Controller stati visivi tastiera
|   |-- AudioLevelView.kt             Visualizzatore livello audio
|-- audio/
|   |-- AudioRecorder.kt              Gestione registrazione
|-- api/
|   |-- GroqApiService.kt             Interfaccia Retrofit
|   |-- TranscriptionRepository.kt    Fase 1, Whisper
|   |-- RefinementRepository.kt       Fase 2, LLM
|   |-- ApiKeyValidatorRepository.kt  Verifica chiave API
|   |-- ApiErrorMapper.kt             Messaggi errore API
|-- settings/
|   |-- PreferencesManager.kt         Gestione preferenze
```

## Licenza

Questo progetto e' distribuito con licenza MIT.
