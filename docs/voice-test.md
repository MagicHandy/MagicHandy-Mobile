# Voice test

The **Voice test** measures the speech recognizers this app could use, on a real phone (plan §19). It decided how voice input, which shipped in 0.1.8 (plan §20, [design](voice-design.md)), listens. It's in debug and phone-test builds only, and it runs voice input's own capture, gain, segmenter, model store and recognizer, so it measures the code the app ships. The release build has voice input but not this page.

## What it compares

| Engine | Runs on | Source |
|---|---|---|
| Parakeet v3, int8 | sherpa-onnx, on the CPU | [csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8), the sherpa-onnx maintainer's export, 670 MB |
| Parakeet v3, 4-bit | sherpa-onnx, on the CPU | [fyychs/parakeet-tdt-0.6b-v3-nbits-sherpa-onnx](https://huggingface.co/fyychs/parakeet-tdt-0.6b-v3-nbits-sherpa-onnx), a community encoder quantized in blocks, which its author found keeps distant speech that the int8 export loses, 697 MB |
| Parakeet v3, full precision | sherpa-onnx, on the CPU | [csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3), the reference, 2.5 GB and about 3.6 GB of memory |
| Google ML Kit, Basic | Google's on-device speech model | [GenAI Speech Recognition](https://developers.google.com/ml-kit/genai/speech-recognition/android), alpha |
| Google ML Kit, Advanced | Gemini Nano, on the Pixel 10 and some other phones | the same API |

- **Parakeet downloads:** the page downloads Parakeet on request. Each file is pinned to a commit and checked against its SHA-256 before use, and an interrupted download resumes.
- **ML Kit downloads:** ML Kit's models come from Google, when the page asks for them.
- **Parakeet's licences:**
  - sherpa-onnx is Apache-2.0;
  - Parakeet is CC-BY-4.0;
  - both fit a GPL-3.0 release.
- **ML Kit's licence:** ML Kit is closed source and runs only while the app is on screen, so using it in a release would need a separate decision.

## Opening it

- Install **MagicHandy Test** on the phone.
- Open **Voice test**, its own icon in the app drawer, or in the app, **Check the microphone** under Settings › Voice › Advanced.
- Its models live apart from voice input's, so downloads here don't count against voice input's, or the other way round.
- The page has the usual Stop bar, which stops every device directly.
- The page itself never sends a message or moves a device.

## The two modes

- **Hands-free** listens continuously:
  - a neural speech detector, Silero or TEN, ends each turn after the chosen silence;
  - Parakeet transcribes the turn;
  - ML Kit hears the same audio in parallel;
  - each result shows the time from the end of speech to the text.

  It keeps listening with the screen off, through a microphone foreground service. ML Kit stops then, by Google's rule, and the page records that. If a call or another app takes the microphone, or it stops working, listening ends and the results say why.
- **One clip, every engine** records one line from the built-in script, or your own wording. It then runs every checked engine on the same recording and marks wrong, missing and extra words against the line.

**Would stop** marks a transcript that the app's typed-Stop rule would act on.

## Microphone settings

- **Audio:** which of Android's microphone paths to record from.
  - **Standard**, the default, is what most apps record. sherpa-onnx's and Home Assistant's Android apps use it.
  - **Speech** is Android's speech-recognition path. Android requires it to have no automatic gain or noise suppression, and its level puts ordinary speech a metre away near −52 dBFS.
  - **Echo cancel** is the call path, with the phone's echo cancellation, noise suppression and gain control.
  - **Raw** is as unprocessed as the phone allows, and about 18 dB quieter than Speech. A phone without it records Standard, and the results say so.
- **Raise quiet speech**, on by default, applies up to 30 dB of gain before the speech detector and the engines hear the audio:
  - it follows the noise floor and the level of the speech, as WebRTC's automatic gain does;
  - speech lands near −26 dBFS, and the noise floor never rises above −50 dBFS;
  - after 10 seconds without speech it forgets how loud the last speech was, so moving away from the phone after speaking close to it doesn't leave the gain at 0 dB;
  - a clip is raised as a whole.
- **Speech starts at** is the detector's confidence that starts a turn, 0.5 by default. Lower hears quieter speech. A turn continues until the confidence drops 0.15 below it for the chosen silence.
- **Padding:** each turn reaches the engines with 0.3 s before the detector heard speech and 0.2 s after it ended, so soft first and last sounds aren't cut.

While listening, the lines under the meter show:

- the microphone's level before any gain, with the noise floor and the gain;
- the detector's confidence, against where speech starts.

The meter's bars light up while the detector hears speech. Each result says how loud the speech arrived and how much it was raised.

**Share** sends every result as text, with the phone model, settings, battery, temperature and memory. If an engine fails, the row says where, and the shared text ends with the last few errors in full.

## A test session

1. **Download** Parakeet int8 and 4-bit, and ML Kit if it offers a download. Tap **Try the sample** to check each engine on sherpa-onnx's sample recording.
2. **One clip, every engine:** read eight script lines at each distance, 30 cm, 1 m and 2 m, first in quiet and then with a device running nearby. Use the same lines each time. Include the Stop lines and "Don't stop."
3. **Hands-free:** for 10 minutes with a device running and nobody speaking, any turn that appears is a false trigger.
4. **Screen off:** start hands-free, lock the phone, and speak a few lines over 30 minutes. Note the battery change and the temperature it reached.
5. **Audio sources:** repeat one distance with **Speech**, **Echo cancel** and **Raw** to see what the phone's processing does. Note the microphone level the listening card shows for each.
6. **Gain:** repeat one distance with **Raise quiet speech** off, to see what the gain adds.
7. **Share** the results.

## What decides the question

- **Accuracy:** whether a bundled Parakeet build holds up at 1–2 m with a device running, compared with ML Kit.
- **Latency:** whether the time from the end of speech to text stays near a second on this phone.
- **Stop:** whether Stop lines are caught reliably, and "Don't stop" never is.
- **Battery and heat:** whether a screen-off session costs a reasonable amount of battery, without throttling.
