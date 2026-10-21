# Hindi and Kannada prototype

Choose Settings → App and voice language → हिन्दी or ಕನ್ನಡ.
Selecting a different language immediately recreates the screens and remembers the selection.
Test voice previews speech; Save stores volume and speed settings.
English remains available. Existing language-index preferences migrate without resetting other settings.

## Coverage
- Screen labels, accessibility labels, status/error messages and spoken prompts.
- All 80 detector object names, quiet descriptions and potential-obstacle alerts.
- Walking maneuver actions, compass headings, roundabout exit numbers and arrival cues.
  Road/place names retain the routing provider's original spelling.
- Destination speech recognition requests the selected language; availability depends on the phone's recognition service.
- Hindi/Devanagari printed-text recognition added. English text remains readable.
- Kannada printed-text recognition is **not supported** by the current ML Kit recognizer.
  The Read screen keeps this limitation visible in Kannada. Recognized text is read as text, not translated.

## Voices
Both speech implementations apply the saved language, speed and volume.
Settings checks voice support and offers Android voice settings if voice data is missing.
Voice loading or missing voice data does not block changing the screen language.
Install the desired voice in Android's speech settings and retry Test voice.
No cloud translation service was introduced.

## Validation
Automated tests cover phrase completeness, formatting arguments, packaged resource parity,
all detector labels, localized maneuver generation, and quiet-mode behavior in both languages.
Device tests check Android resource resolution and report installed TTS language availability without speaking.

Native Hindi and Kannada speakers still need to review wording and pronunciation.
Physical navigation, microphone recognition and Hindi camera OCR require real-world testing.
Switching language restarts the activity and ends any active navigation session.

## Phone checklist
1. Select each language without pressing Save; check that every tab changes language and remains changed after restart. Test each voice.
2. Describe a chair/phone; verify native names and no continuous narration while idle.
3. Check Repeat and Stop, then verify route instructions in a supervised test.
4. Read clear printed Hindi and English samples. Verify the Kannada OCR limitation stays visible.
5. Test large system text and TalkBack; check buttons for wrapping and clipping.
6. Return to English and confirm normal operation.

## Results on 2026-10-20
- Debug build and APK signature verification passed; 23 JVM tests passed.
- Updated APK installed on Samsung Galaxy A34, preserving existing data.
- Three device tests passed, including actual activity localization and tab preservation after recreation.
- Android reports en-IN, hi-IN and kn-IN as language/country available (1).
- Test language changes were restored. Pronunciation, camera OCR, microphone recognition and field navigation remain untested.
- Follow-up language-selection fix installed successfully. All four phone tests passed, including selecting Hindi, Kannada and English through the actual dropdown without Save and checking visible labels after recreation. Original language restored and app reopened.
