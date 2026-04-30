# KeyVoice 🎙️

**Tastiera vocale Android con trascrizione intelligente**

KeyVoice è una tastiera personalizzata (IME) per Android che trasforma la voce in testo utilizzando le API Groq. Supporta la trascrizione vocale tramite Whisper e il raffinamento automatico del testo tramite un modello LLM.

## 🎯 Funzionalità

- **Tastiera vocale** — Un solo pulsante microfono, nessuna QWERTY
- **Trascrizione con Whisper** — Modello `whisper-large-v3-turbo` di Groq
- **Correzione automatica** — Punteggiatura, grammatica e errori di trascrizione corretti da LLM
- **Funziona ovunque** — WhatsApp, Chrome, Note, email e qualsiasi app con input testuale
- **Tema chiaro/scuro** — Si adatta automaticamente al tema di sistema
- **Indicatore audio** — Visualizzatore livello audio in tempo reale durante la registrazione
- **Annulla rapido** — Pulsante per rimuovere l'ultimo testo inserito

## 📋 Requisiti

- Android 8.0+ (API 26+)
- Connessione Internet
- Chiave API Groq (gratuita)

## 🔑 Ottenere una API Key Groq

1. Vai su [console.groq.com](https://console.groq.com)
2. Crea un account gratuito (login con Google/GitHub)
3. Vai nella sezione **API Keys**
4. Clicca su **Create API Key**
5. Copia la chiave generata

> **Nota:** Groq offre un piano gratuito generoso con limiti di rate sufficienti per uso personale quotidiano.

## 📦 Installazione

### Da Android Studio

1. Clona il repository:
   ```bash
   git clone https://github.com/your-username/KeyVoice.git
   ```

2. Apri il progetto in Android Studio (Hedgehog 2024.1+ consigliato)

3. Sincronizza Gradle e compila il progetto

4. Installa l'APK sul tuo dispositivo:
   ```bash
   ./gradlew installDebug
   ```

### Primo avvio

1. Apri l'app **KeyVoice** dal launcher
2. Segui i 3 passaggi di configurazione:
   - **Passo 1**: Attiva KeyVoice nelle impostazioni tastiera di sistema
   - **Passo 2**: Seleziona KeyVoice come tastiera attiva
   - **Passo 3**: Inserisci la tua API Key Groq nelle impostazioni

## 🎤 Utilizzo

1. Apri qualsiasi app con un campo di testo (WhatsApp, Note, Chrome, ecc.)
2. La tastiera KeyVoice apparirà con il pulsante microfono
3. **Tocca il microfono** per iniziare a registrare (diventa rosso)
4. **Tocca di nuovo** per fermare la registrazione
5. Attendi la trascrizione e il raffinamento automatico
6. Il testo corretto viene inserito automaticamente nel campo

### Pulsanti della tastiera

| Pulsante | Posizione | Funzione |
|----------|-----------|----------|
| 🎙️ Microfono | Centro | Avvia/ferma registrazione |
| ⌨️ Tastiera | In basso a sinistra | Passa alla tastiera precedente |
| ↩ Annulla | In basso al centro | Rimuove l'ultimo testo inserito |
| ⚙ Impostazioni | In basso a destra | Apre le impostazioni |

## ⚙️ Impostazioni

| Opzione | Descrizione | Default |
|---------|-------------|---------|
| API Key | Chiave API Groq (salvata crittografata) | — |
| Lingua | Italiano, English, Auto-detect | Italiano |
| Modello LLM | llama-3.3-70b-versatile / llama3-8b-8192 | llama-3.3-70b-versatile |
| Fase 2 | Abilita/disabilita correzione LLM | Abilitato |
| Durata max | Durata massima registrazione (30s–10min) | 3 minuti |
| Feedback aptico | Vibrazione al tocco del microfono | Abilitato |

## 🔧 Dettagli tecnici

### Pipeline di elaborazione

```
Audio (M4A/AAC) → Whisper API (Fase 1) → Testo grezzo → LLM API (Fase 2) → Testo raffinato → InputConnection
```

### Limite di registrazione

La durata massima è configurabile (default: 3 minuti) per due motivi:
- L'API Whisper ha un limite di 25 MB per file
- Con M4A/AAC a qualità standard, 3 minuti producono file di circa 1-2 MB, ben sotto il limite

Negli ultimi 10 secondi appare un countdown visivo, e al raggiungimento del limite la registrazione si ferma automaticamente.

### Stack tecnologico

- **Kotlin** con Coroutines
- **InputMethodService** per l'integrazione IME
- **MediaRecorder** per la registrazione audio (M4A/AAC)
- **Retrofit + OkHttp** per le chiamate API
- **EncryptedSharedPreferences** per la sicurezza della API key
- **Material Design 3** con supporto tema chiaro/scuro

### Architettura

```
com.keyvoice.app/
├── ime/VoiceKeyboardService.kt      — Servizio IME principale
├── ui/
│   ├── KeyboardViewController.kt    — Controller stati visivi tastiera
│   └── AudioLevelView.kt            — Visualizzatore livello audio
├── audio/AudioRecorder.kt           — Gestione registrazione
├── api/
│   ├── GroqApiService.kt            — Interfaccia Retrofit
│   ├── TranscriptionRepository.kt   — Fase 1 (Whisper)
│   └── RefinementRepository.kt      — Fase 2 (LLM)
├── settings/
│   ├── SettingsActivity.kt          — Schermata impostazioni
│   └── PreferencesManager.kt        — Gestione preferenze
└── MainSetupActivity.kt             — Guida primo avvio
```

## 📄 Licenza

Questo progetto è distribuito con licenza MIT.

---

**KeyVoice** — La tua voce, le tue parole, corrette automaticamente. 🎙️✨
