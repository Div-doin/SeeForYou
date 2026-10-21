# Navigation update

The Detect screen now uses this flow: tap Navigate, say a place and city, hear and inspect the matched address, then choose Start walking or Cancel. While navigating, the Navigate button becomes Repeat direction. Stop Nav cancels pending searches, requests, and location tracking. Leaving Detect or backgrounding the app stops navigation; background navigation is not implemented.

Destination matching still uses Android Geocoder's first match. Confirmation is spoken but the Start/Cancel choice uses buttons (accessible with TalkBack), not voice yes/no. Include the city in the destination to reduce ambiguity.

## Changes
- Parse OpenRouteService waypoint arrays correctly and use each instruction's endpoint for step progression.
- Request a fresh GPS fix, reject stale or inaccurate positions, and report GPS loss.
- Give one approach cue per step, announce the next maneuver near the endpoint, and report proximity to the mapped destination rather than guaranteeing the entrance was reached.
- Recalculate after three accurate off-route readings, with a 30-second request cooldown.
- Cancel pending requests and ignore results from stopped navigation sessions.
- Serialize speech updates; urgent object alerts interrupt navigation and pending messages expire instead of building an unlimited backlog.
- Remove unsupported meter estimates from camera obstacle announcements. Bounding-box size is still used as a heuristic for alert priority.
- Search destinations away from the UI thread and avoid speaking into the microphone during recognition.

## Validation on 2026-10-18
- assembleDebug and testDebugUnitTest succeeded.
- Seven navigation tests passed: waypoint parsing, malformed waypoint rejection, departure handling, approach/turn/arrival timing, inaccurate GPS filtering, sustained deviation/reset, and distance to a route segment.
- APK signing verified.
- A live request with fixed public test coordinates timed out. Provider availability and the existing routing credential have not been verified. No phone location was used for this check.
- Phone disconnected before installation; the new build has not been tested on-device.

## Next phone checks
1. Say a destination including the city. Check the full matched address and cancel; no route should start.
2. Repeat and confirm. Check that a route summary or clear GPS/network error is spoken.
3. Check Repeat direction and Stop Nav. Stop while a route is loading; a late response must not restart guidance.
4. Test denial of microphone/location permission and retry after granting permission.
5. With a sighted tester on a familiar walking route, check advance turns and controlled deviation. Test GPS loss and network failure, too.
6. Switch tabs and background the app; navigation should stop.

This prototype does not determine safe crossings, sidewalk accessibility, entrance location, or a collision-free path. GPS thresholds and turn behavior require supervised field evaluation. Route matching remains heuristic, particularly at loops, closely spaced turns, parallel paths, and missed maneuvers. The existing API credential remains in the prototype source; production distribution needs credential management and a separate security review.

Provider reference: https://giscience.github.io/openrouteservice/api-reference/endpoints/directions/
