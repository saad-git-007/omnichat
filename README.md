# OmniChat

**A private, on-device AI assistant for Android that sees, hears, reads and speaks, running entirely on the phone.**

OmniChat runs [LFM3-Omni-2B](https://huggingface.co/LiquidAI/LFM3-Omni-2B-GGUF), a 2-billion-parameter multimodal model, locally through [llama.cpp](https://github.com/ggml-org/llama.cpp). It takes text, photos, audio, voice and documents, answers in text or out loud, and can act on the phone (alarms, timers, calendar, message drafts). Nothing you send leaves the device. The only exception is web search, which is off unless you turn it on, and the app tells you when it is on.

It was built and tuned for the **Samsung Galaxy S23 Ultra** (Snapdragon 8 Gen 2), where the model runs on the **Hexagon NPU** at about 28 tokens/s. It also runs on the CPU or GPU of other recent arm64 Android phones.

> Sideload only: there is no Play Store listing and no Google sign-in. Release builds are signed with the debug key.

---

## Features

### Chat with any kind of input
- **Text, photos, audio and documents** in one conversation, with Markdown replies.
- **Documents:** PDF, Excel (.xlsx), CSV, Markdown, JSON and plain text.
  - PDF tables are rebuilt as real tables from where each word sits on the page, so values stay under the right column.
  - Two-column pages are read column by column.
  - Scanned pages, and pictures inside text PDFs (screenshots, photos of notes), are read with on-device OCR (Xiaomi-OCR-0), which keeps tables as tables.
  - Photos: the chat model reads most text in a photo itself, in seconds on the NPU. A quick check sends only tables, receipts and forms (or a request for the exact text) through the OCR model as well, since that's where the chat model loses track of rows and columns.
  - Excel workbooks become exact CSV, one section per sheet, in tab order.
  - Questions about a table get help with the reading: the app finds the rows the question names, works out exact counts and totals for a value it names ("West", "Priya") and summarises the columns it mentions, and puts these next to the question. Small sheets are given to the model one row per line with every value named, and empty cells marked. On a 41-question test this took the model from 18 to 31 correct answers.
- **Long documents:** whatever doesn't fit in the context is indexed and searched by meaning for each question (up to 4M characters).
- **Long recordings:** audio longer than 10 minutes is transcribed in parts, then answered from the transcript.
- **Fast follow-ups:** the processed state of a document is cached on disk, so asking more questions about the same file starts instantly.

### Voice
- **Voice chat:** speak, and the model answers out loud. 19 languages, female or male voice. The model generates the speech itself; there's no separate TTS engine.
- **Live camera in voice chat:** turn on the camera and ask about what you're showing it ("what is this?", "what does this label say?"). The preview runs smoothly, and the frame current when you finish asking goes with your question. The model doesn't watch it as video: each picture costs ~1.3 s on the NPU, so one well-timed frame per question is both faster and clearer. With the camera on it's a hands-free conversation: tap once, then just talk. It sends when you pause, answers out loud and listens again, so you can keep showing it things and asking follow-ups ("and this one?", "which of the two is cheaper?"). Each question takes a fresh frame, and the earlier ones and their answers stay in the same chat. When the context gets close to full, the chat is rebuilt from its text, so a long session doesn't stop: earlier pictures are kept by name along with what was said about them. Tap while it's listening to pause. A small line shows the speed: how long it took to read what you showed and said, tokens per second while writing, and how fast the voice is made compared with real time (it can be hidden in Settings). Front or back camera; off when you leave voice chat.
- **Dictation:** tap the mic to type by voice. Slide up to send a voice note instead.
- **Live transcribe:** a separate page that keeps writing down what it hears. You choose the model in Settings. **Omni** (the chat model) writes action items and notes while the meeting is still going. **Audio** (LFM3-Audio, a speech model swapped in while it listens, a few seconds each way) is faster, and its notes come when you stop; until its 1.35 GB download finishes, the chat model transcribes. On 73 LibriSpeech recordings it matched LFM3-Omni on clear speech (5.7% word errors), made a fifth fewer errors in noise (12.5% vs 15.5%) and was 18–27% faster. It keeps going in the background with the screen off, and you can copy or save the transcript with or without time stamps. The block length (3–30 s) is set in Settings; scrolling up to read stops the page from jumping to new text until you come back down; quiet stretches are skipped instead of being written down as "Thank you."
- **Meeting notes:** written by the chat model. With LFM3-Audio transcribing they're written when you stop (it's a speech model: asked for notes it found 0 of 5 action items); otherwise while live transcribe listens, action items (who, what, by when), decisions and the key points of each minute are noted between pieces, and a summary is written when you stop. Transcription always comes first: a note in progress stops the moment the next piece is ready. Action items are found by picking out sentences that sound like a request or a promise and asking the model about each one with the sentences around it; decisions are taken word for word. On scripted 10- and 30-minute meetings, with and without speech-recognition errors, it found all 26 action items and invented none.
- **Around the transcript:** bookmarks, search, auto-save (a crash or restart loses nothing), and export as text, Markdown with the notes, or subtitles (.srt). "Ask about this meeting in chat" opens a chat with the transcript and notes attached.

### Tools the model can use
The model decides when to use a tool. App-opening actions always stop for you to confirm in the other app, and they only run when your message actually asks for them.

| Tool | What it does |
|---|---|
| Set alarm, set timer | Opens Clock with the alarm or timer filled in |
| Add calendar event, read calendar | Creates an event or answers "what's on Friday?" |
| Draft text message | One draft per recipient. With SMS permission, one "Send to all" tap |
| Draft email, dial, open map, open website | Opens the right app with everything filled in |
| Contacts, clipboard, battery and device status | Answers from the phone |
| Calculator | Exact arithmetic, percentages and powers |
| Web search (optional) | Exa and Firecrawl. Works without keys, or with your own keys. Answers cite numbered sources you can tap |
| Search past chats | Finds earlier conversations by meaning |
| Search gallery | "Photos of my dog at the beach last summer": photos are indexed on the device by what they show |

### Private memory and search
- **Chat history** stays on the phone and can be searched by meaning with EmbeddingGemma.
- **Gallery search** builds an image index in the background. It pauses while the model is answering.

### Speed and hardware
- **Backends:** Hexagon NPU, OpenCL (Adreno GPU), Vulkan or CPU, chosen in Settings, with automatic fallback. If a GPU driver can't run the model, the app marks it as broken and switches to CPU.
- **Optional "fast head":** shrinks the model's output layer once on the device, for faster generation.
- **Instant first reply:** the system prompt state is cached on disk, so a new chat starts at once instead of re-reading about 1,300 tokens.
- **Keeps working in the background:** a foreground service lets a reply finish with the app in the background and notifies you when it's ready.

### Built-in diagnostics
- **Stats page:** tokens/s, prompt processing time, time to first token and peak RAM for every reply, with comparison charts and CSV export.
- **Logs page:** this run, the previous run and the last crash (decoded from Android's crash record), with copy and share.
- **NPU op profiling** for performance work.

### Design
- Dark "night gradient" look with glass panels and an animated orb.
- A voice glow that reacts to the microphone.
- A reduce-motion option.
- Custom instructions that are added to every chat.

---

## Measured performance (Galaxy S23 Ultra)

| Backend | Generation | Notes |
|---|---|---|
| Hexagon NPU (HTP v73) | ~28.5 tok/s | Image prompt of 768 tokens processed in 0.57 s; image encoder on the NPU takes 1.3 s |
| CPU, 4 threads | ~24 tok/s | |
| OpenCL (Adreno 740) | ~14 tok/s | |
| Vulkan | not usable | The Adreno driver can't build the matmul pipelines; detected automatically |

---

## Models

Downloaded inside the app on first run from Hugging Face and checked with SHA-256. They're stored in `Android/data/com.saad.omnichat/files/models`.

| Model | Size | Used for |
|---|---|---|
| LFM3-Omni-2B Q4_0 + multimodal projector Q8_0 | 1.6 GB | Chat, vision, audio, speech (required) |
| EmbeddingGemma 2 Q8_0 (+ vision projector) | 0.3 GB (+0.55 GB) | Smart search (+ gallery search) |
| Xiaomi-OCR-0 Q4_K_M + projector F16 | 0.75 GB | Reading scanned pages and text in pictures, tables included |
| LFM3-Audio-2B Q4_0 + projector Q8_0 | 1.35 GB | Live transcribe (swapped in for the chat model while it listens) |

The models need about 4.5 GB in total with every feature on. Chats and search indexes add only tens of MB, even after thousands of messages.

---

## Project layout

```
app/src/main/
├── cpp/                  JNI bridge to llama.cpp + mtmd (omni_jni.cpp), CMake build
└── kotlin/com/saad/omnichat/
    ├── llm/              OmniEngine (chat, voice, tools), Ocr, Embedder, native bindings
    ├── media/            PDF/Excel reader + PDF table layout, audio decoding, mic, speech player
    ├── memory/           Semantic index for chats and documents, gallery index
    ├── tools/            Phone tools, tool-call parsing, SMS sender
    ├── net/              Web search (Exa / Firecrawl)
    ├── history/          Chat storage
    ├── stats/            Per-reply performance records
    ├── model/            Model downloads and checks
    └── ui/               Compose UI: chat, voice, live transcribe, stats, logs, theme, orb, voice glow
```

---

## Building

**Requirements**
- Android SDK with compileSdk 37
- NDK 30.0.16248370
- CMake 4.1.2
- JDK 17+
- Linux x86_64 host (used for development)

1. **Clone the repo and fetch the native dependencies.** They go in `../third_party`, next to the repo, at the exact commits the app was built with.

   ```bash
   git clone https://github.com/saad-git-007/omnichat.git
   cd omnichat
   ./scripts/fetch-deps.sh             # llama.cpp + Vulkan/SPIR-V/OpenCL headers
   ./scripts/fetch-deps.sh --opencl    # optional: OpenCL GPU backend
   ./scripts/fetch-deps.sh --hexagon   # optional: Snapdragon NPU backend (Hexagon SDK, large download)
   ```

   The OpenCL and NPU backends are switched on automatically when their files exist, and left out otherwise. llama.cpp is used unmodified.

2. **Build.** The NPU build needs `ninja` on `PATH`; the one bundled with the SDK's CMake works.

   ```bash
   PATH=~/Android/Sdk/cmake/4.1.2/bin:$PATH ./gradlew assembleRelease
   adb install app/build/outputs/apk/release/app-release.apk
   ```

3. **Run the unit tests** (calculator, tool-call parsing and gating, PDF table layout, OCR output cleanup, table hints, speech detection, meeting notes, transcript exports):

   ```bash
   ./gradlew :app:testDebugUnitTest
   ```

The APK includes `x86_64` libraries so it also runs on the Android emulator, on CPU only.

---

## Privacy

- The model, OCR, speech and search indexes all run on the phone.
- Chats, indexes and logs are stored in the app's private storage.
- Web search is off by default. When it's on, the search query goes to Exa or Firecrawl to find sources, and the app shows a notice saying so.
- API keys are optional, entered by you in Settings, and never built into the app.

---

## Credits

- [LFM3-Omni-2B](https://huggingface.co/LiquidAI/LFM3-Omni-2B-GGUF) by Liquid AI
- [llama.cpp / ggml](https://github.com/ggml-org/llama.cpp), including the mtmd multimodal library and the Hexagon backend
- [EmbeddingGemma 2](https://huggingface.co/ggml-org/embeddinggemma-2-GGUF) by Google
- [Xiaomi-OCR-0](https://huggingface.co/xiaomi-research/Xiaomi-OCR-0) by Xiaomi, as the [GGUF build by richardyoung](https://huggingface.co/richardyoung/Xiaomi-OCR-0-GGUF)
- [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android)
- The orb is a Kotlin port of [Thinking Orbs](https://libraries.dev/orbs) by Jakub Antalik (MIT, license text in `ui/orbs/OrbEngine.kt`). The voice glow is a port of [Voice Glow](https://libraries.dev/voice), also by Jakub Antalik (MIT, license text in `ui/voiceglow/`).

Each model is covered by its own license; check the model cards before redistributing.
