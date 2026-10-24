# Diagnostics: logs and JSON

Settings → Diagnostic report opens **Logs**. Switch to **JSON** for the full existing report plus `diagnostic_log`. Copy logs / Copy JSON copies the displayed view and does not close the reader.

Logs include timestamps with milliseconds, severity labels, component, localized explanations and technical details. The header identifies the app version, version code, core build, device, Android version, session and timezone. Each event also has a sequence number and monotonic elapsed milliseconds, so ordering remains clear if the device clock changes.

Imports record start, completion, warnings, cancellation, cache restoration, unchanged sources and failure. `operation_id` connects an import's events. Playback records connection attempts, state transitions, errors, recovery triggers, retry scheduling and recovery success. User library failures record the affected storage stage.

Available error details include fixed error codes, HTTP status, Media3 numeric code and symbolic name, system errno, exception class/cause classes, retryability and terminal status. Playback records attempt ID, buffer/position milliseconds, received bytes and sampled throughput. Exception messages, provider names, URLs, usernames and passwords are excluded from event details. Values pass through a field allowlist.

History retains the latest 500 events in memory for the current app process. It survives closing the dialog and Activity recreation, and resets after process restart. The reader reports omitted event counts. Existing previous-process-exit information remains in the report and appears in the captured-state summary when Android provides it.

The state summary is captured when the dialog opens. Events follow live at the end of the list. Reading earlier entries freezes the displayed event snapshot and offers **New entries · go to latest**. Logs and JSON share that snapshot; switching views preserves separate scroll positions. JSON remains frozen while being read. Returning to logs offers newer events if available.

Remote controls: ↑/↓ scroll, ← opens view selection, → focuses Copy, Back closes. Large system text uses stacked footer actions. The native reader exposes accessibility scrolling; severity is conveyed through text as well as color.

Validation covers bounded/concurrent event recording, clock changes, sensitive-field exclusion, copy context, real D-pad navigation, JSON field preservation, copying, live arrivals, 200% text, accessibility scrolling, HTTP 404/410 playback recovery and import/cache/failure events. Fixtures use local synthetic sources.
