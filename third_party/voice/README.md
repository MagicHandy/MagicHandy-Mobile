# Voice input: third-party code and models

Voice input (docs/voice-design.md) ships two native libraries in the APK and downloads its models on the phone.

## In the APK

| Library | Version | License |
|---|---|---|
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | 2.0.3, its Android archive for `arm64-v8a` | [Apache-2.0](sherpa-onnx-LICENSE.txt) |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime), bundled in that archive | As bundled | [MIT](onnxruntime-LICENSE.txt) |

Both license texts also ship inside the APK, under `META-INF/magichandy/`.

## Downloaded when you set up voice

The app fetches these from pinned URLs and checks each file's SHA-256 (`ModelFiles.kt`). They're never part of the APK.

| Model | Used for | License |
|---|---|---|
| [Parakeet TDT 0.6B v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3) by NVIDIA, in [sherpa-onnx's exports](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8) | Speech to text: Compact (int8) or Full precision | [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) |
| [Silero VAD](https://github.com/snakers4/silero-vad) | Hearing where speech starts and ends | [MIT](silero-vad-LICENSE.txt) |
| [TEN VAD](https://github.com/TEN-framework/ten-vad) | The test builds' other speech detector | [Its own license](https://github.com/TEN-framework/ten-vad/blob/main/LICENSE) |
