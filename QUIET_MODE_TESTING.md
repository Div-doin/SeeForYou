# Quiet mode

The camera starts silently. Describe surroundings reads a single summary of up to three stable, confident object labels, using positions within the camera image. Read text opens the existing OCR screen. Repeat repeats the recent description, or the current direction during navigation. A description older than 15 seconds is refreshed instead of repeated.

Stop speaking pauses camera-screen speech, including navigation and obstacle alerts. Resume speech, Describe, Repeat, or starting navigation resumes it. There is no always-listening voice command mode in this version. Text Reader has separate Stop speaking and Repeat controls; stopping also prevents an in-flight OCR result from starting speech until another user reading action.

## Automatic announcements

Only active navigation enables automatic camera alerts. Laptops, phones, and other routine objects are never announced automatically. Selected potential obstacles require confidence of at least 0.65, three consistent analyzed frames, a central image position, and substantial image height. These are prototype heuristics, not evidence of a collision or measured distance.

An unchanged object label is announced once. Reannouncement requires at least five seconds of absence or a confirmed image-height increase of at least 35 percent. There is a six-second minimum gap between alerts. The tracker groups by label, not individual object identity; two people may be treated as the same tracked category. Some real obstacles will be missed. Silence does not mean a clear path.

Directions take precedence over scene descriptions. Potential-obstacle alerts can interrupt directions. Old pending speech expires. Live visual detector labels are excluded from TalkBack focus to avoid duplicated changing announcements; descriptions are available through the labeled button. Controls can scroll with larger text settings.

## Checks

Ten quiet-scene regression tests cover routine-object suppression, descriptions on request, stability, prolonged unchanged scenes, temporary dropout, reappearance, size changes, low-confidence/side/small detections, stale scenes, and summary length. The seven navigation tests remain in the suite.

On the phone, verify:
1. Aim at a laptop and phone for a minute without pressing anything: no object narration.
2. Tap Describe after holding the phone steady: one concise summary, then silence.
3. Try Repeat, Stop speaking, and Resume speech. Stop must silence both current and queued camera-screen speech.
4. During navigation, routine objects remain silent. Stable potential obstacles should not repeat merely because time passes.
5. Read a long printed paragraph, stop speech, and repeat it without recapturing.
6. Switch screens during capture and recognition: old results must not start reading later.
7. Use TalkBack and enlarged text to reach all actions.

Field testing with blind users is still needed. Live detection history was previously tied to automatic speech; this update removes that repeated logging path along with the repeated narration. Existing history and OCR logging remain available.
