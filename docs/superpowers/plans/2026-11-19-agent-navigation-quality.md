# Navigation and observation quality fixes

Goal: apply the user-approved review fixes on main@8dd967d with one writer, no device deployment or paid model calls.

Implementation sequence:
- [x] Preserve POI metadata, collect before truncation, rank matching transit/landmarks before incidental businesses, respect explicit business names. Ranking fixture includes an explicit-hotel counterexample.
- [x] Use a higher quality text-capture profile, preserve validated original JPEG bytes, retain bounds/memory limits. Verified original-byte preservation and invalid-image rejection.
- [x] Start recording on the initiating press; add the measured RGR06 middle-click navigation shortcut. Consume only identified ring input; system-reserved long presses are not business controls, function-key clicks are opt-in. Older hosts retain their physical confirmation window through capability negotiation.
- [x] Deliver deterministic navigation reminders outside the model queue, recover pending feedback when the turn budget is exhausted, and allow destination changes during navigation, including the new candidate question and confirmed route replacement. Routine telemetry updates facts and consumes core sequence/policy checks without a model call.
- [x] Rechecked crossing context: ConcreteToolGatewayAdapter already injects it. The earlier review concern missed that layer; no redundant patch was made. Missing route/camera evidence continues to fail closed.
- [x] Focused validation: 37 Node scenarios/source contracts, 11 Android unit tests, both Kotlin modules compiled, TypeScript compilation and contract validation passed. Hardware acceptance remains separate.

Sources: GitHub issues #44, #45, #24, #41; rgr06-adb-events.md. Confirmed ring left=83→66, middle=83→85. Ring left long press invokes the OEM assistant; no global key interception is claimed. Existing local roadmap was preserved outside the checkout before switching branches.
