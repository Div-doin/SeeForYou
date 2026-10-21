# Hands-free commands prototype

Enable hands-free commands once using the button above the tabs and allow microphone access.
Keep SeeForYou open. Say **“Hey SeeForYou”**, pause for the beep, then say one command.
Each command needs a new wake phrase. The listener stops when the app goes into the background.
The screen stays awake while hands-free is enabled. Turn it off with the same button.

| Action | English | Hindi | Kannada |
|---|---|---|---|
| Read camera text | Read the text | पाठ पढ़ो | ಪಠ್ಯ ಓದಿ |
| Describe | Describe surroundings | सामने क्या है | ಮುಂದೆ ಏನಿದೆ |
| Repeat | Repeat | फिर से बोलो | ಮತ್ತೆ ಹೇಳಿ |
| Hindi | Change the language to Hindi | हिंदी में बदलो | ಹಿಂದಿಗೆ ಬದಲಿಸಿ |
| Kannada | Change the language to Kannada | कन्नड़ में बदलो | ಕನ್ನಡಕ್ಕೆ ಬದಲಿಸಿ |
| English | Change the language to English | अंग्रेजी में बदलो | ಇಂಗ್ಲಿಷ್ |
| Disable hands-free | Stop listening | सुनना बंद करो | ಕೇಳುವುದನ್ನು ನಿಲ್ಲಿಸಿ |

Language commands change both screens and app speech and persist across restarts.
Commands spoken in another language do not implicitly change the app language.
Read opens the camera reader, waits for camera binding, captures once and reads the result.
The existing Kannada printed-text OCR limitation remains; Kannada commands do not add Kannada OCR.

## Recognition and limitations
- Wake detection uses a bundled offline English Vosk model and a constrained phrase grammar.
  The same English wake phrase is used in every app language.
- After waking, Android's speech service receives one command (15 second timeout).
  It may use the network, depending on the installed service.
- On Android 14+, automatic switching between en-IN, hi-IN and kn-IN is requested.
  Support is provider dependent and may require downloaded speech models.
  Older/unsupported recognizers generally use the selected app language.
  If cross-language recognition fails, change language using Settings first.
- Wake listening pauses during app speech and destination microphone input to avoid self-triggering
  and microphone conflicts. It resumes after speech completes. Use the on-screen Stop control
  to interrupt long reading; the wake phrase is not active during that speech.
- This is foreground hands-free operation, not a lock-screen/background assistant.
- Navigation destination collection remains separate. Voice commands do not trigger SOS.
- Acoustic wake accuracy and Hindi/Kannada pronunciation still need testing with the user's voice.

## Third-party components
- Vosk Android 0.3.75, Apache 2.0: https://github.com/alphacep/vosk-api
- Vosk small US English 0.15 model, Copyright 2020 Alpha Cephei Inc., Apache 2.0:
  https://alphacephei.com/vosk/models
  Model README and Apache license are bundled alongside the model in assets.
- JNA 5.18.1: https://github.com/java-native-access/jna (Apache 2.0 / LGPL 2.1 dual license).

## Validation
Unit tests cover exact command matching in all three languages and rejection of unrelated,
negated and ambiguous phrases. Device tests load the real packaged wake model and check
that silence and a synthesized unrelated sentence do not trigger it, while a synthesized
“Hey SeeForYou” sample does. The fixtures use Windows speech synthesis at 16 kHz mono PCM.
Device tests also check overlapping speech-gate ownership and foreground microphone startup/shutdown.
Live acoustic wake, multilingual speech-service behavior, and camera reading require a phone trial.

Final validation collected 2026-10-21: 27 unit tests and all 7 phone tests passed.
The signed hands-free APK was installed on Galaxy A34 with existing app data preserved.
The phone was disconnected at the final continuation, so it could not be reopened remotely.
Real human speech and cross-language speech-provider behavior still require user testing.

2026-10-21 recognition follow-up: added Hindi mein baat karo / हिंदी में बात करो,
polite command forms and alternative-transcript handling. Unmatched commands show the
recognized words; missing transcripts have a separate message. Automatic switching failures
fall back to the selected language on subsequent attempts in the same app instance.
29 unit tests passed and updated APK installed. Live recognition still awaiting user confirmation.

Phone recognition fix: commands now prefer Android on-device recognition on Android 13+.
Automatic switching is restricted to models the recognizer reports as installed.
Galaxy A34 now has en-IN and hi-IN recognition models installed after Hindi model setup.
Its on-device service does not list Kannada recognition support: Kannada UI and spoken output
remain available, but Kannada voice input is not verified/supported by this offline service.
The default service previously returned no words even with automatic switching disabled.
Live English/Hindi command verification remains pending user trial.

### Pending no-words investigation
The user still reports no recognized words with English and Hindi models installed. Added timing, readiness, speech detection and aggregate signal-level diagnostics, plus wake microphone-release timing. These diagnostics do not log spoken words or audio. Device capture is pending USB reconnection; do not treat the provider change as a verified fix.


### Controlled voice comparisons (2026-10-21)
Debug launches accept `--es voice_diagnostic PROFILE`; no persisted setting is changed. Recognized commands announce "Test command recognized" rather than performing the action, keeping language and screen constant. Normal launches retain existing behavior.

Profiles (all keep the same wake handoff and on-device provider):
- BASELINE: en-IN, automatic switching among installed models, app beep, 1500 ms silence override.
- TIMING: baseline with only the custom silence override removed (provider defaults).
- NO_BEEP: baseline with only the app ToneGenerator beep omitted; watch the Listening status. Any service-generated sound is outside this toggle.
- ENGLISH: baseline with automatic switching disabled, en-IN fixed.
- HINDI: baseline with automatic switching disabled, hi-IN fixed.

Each diagnostic profile keeps its switching policy stable across retries (normal fallback is unchanged). Use the same English phrase twice per BASELINE/TIMING/NO_BEEP/ENGLISH profile. For Hindi compare the same Hindi phrase in BASELINE versus HINDI. Wait for the listener to be ready. Log the actual automaticSwitch value: installed model discovery must complete before an automatic-mode comparison is valid. Compare readiness-to-result timing, speech detection, RMS sample counts, result counts and commandMatched. Do not treat missing RMS callbacks as proof of silence. Do not infer a cause from one successful trial. Real human trials are pending.

Android cautions custom silence/minimum-duration extras may be ignored or behave unexpectedly: https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS

### Controlled results and command timing change
- TIMING: one observed live success; auto-switch and beep both enabled. Result at 5793 ms from start (5475 ms after ready), 3 alternatives, commandMatched=true.
- NO_BEEP: two observed failures with zero alternatives, despite speech detection. Result timing after ready was 5519 ms and 1628 ms. Removing the beep alone was insufficient.
- ENGLISH: two observed failures with auto-switch disabled and speech detected. Results after ready at 1665 ms and 4116 ms, zero alternatives. Disabling switching alone was insufficient.
- Normal hands-free commands now omit the custom complete-silence extra, matching the successful TIMING profile. Navigation retains its existing setting; explicit diagnostic comparison profiles retain the old value. Beep and installed-model language switching remain enabled.
- Evidence implicates the custom endpoint setting, not merely total session duration. One success is not proof of reliability. Normal command execution and Hindi-language speech still require live confirmation. Fixed-Hindi diagnostic has not been run.

### Language recreation crash fix
A live 'Speak in Hindi' command succeeded, then camera inference crashed in native TensorFlow Lite during screen recreation. Detector inference and close now serialize, close is idempotent, and queued calls after close return no detections. Camera ignores inactive frames and stale UI/binding callbacks. Phone regression tests passed: concurrent queued inference/shutdown and four voice-command language changes with camera active. These test command dispatch directly, not human speech recognition. Final build passed 30 JVM tests and both device tests. Added narrowly scoped 'chil the language to hindi' alias reported by user, with negation and ambiguity regression checks. General transcription quality remains dependent on the phone recognizer.

### Everyday phrases and reading recovery
Added bounded request composition for language aliases and common Hindi transliterations/mixed-script requests (English mai baat kro, angrezi mein bolo, mujhse English mein baat kariye), plus natural reading, description, repeat, stop and settings phrases. Full-utterance matching and ambiguity/negation checks remain; this is not unrestricted language understanding.

Speech diagnostics showed repeated service disconnections (11) and a client error (5). Broken/cancelled recognizers are rebuilt on the next command; stale callbacks are ignored and idle cancel no longer contacts the service. OCR now pauses wake recognition throughout capture, hands the pause to TTS, and releases on stop/failure/lifecycle exit. Pending speech initialization holds the gate; stopped and errored utterances release it.

Validation: 31 JVM tests passed; debug APK signature verified and installed. ReadingVoiceRecoveryTest passed on Galaxy A34 in 18.121 seconds: wake listener returns after two fixed-text readings and stopping a third. Test uses actual OCR speech path but does not capture private text or test human ASR. Live OCR-to-command trial and forced service-disconnection recovery remain unverified.
