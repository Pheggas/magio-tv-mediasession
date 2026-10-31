# V2 control plane (not implemented in V1)

V1 is observation-only. This directory is reserved for the optional V2
channel/EPG/control feature.

Contract:

- Do not modify `NowPlayingState`, `NowPlayingCodec`, `NowPlayingStore`, or
  `HermesSession.publish()` for V2.
- A V2 `ControlServer` may call `HermesSession.store()` and register a
  `NowPlayingStore.Listener`.
- Start V2 only from a `FEATURES=media+control` build; V1 builds must not open
  a port or add a server.
- Reuse Magio's existing MCORE endpoints for channel list/EPG and the existing
  `START_LIVE` router action for tuning; do not build a second data API.
- If V2 creates a `media_player`, it must be optional and disabled by default
  when a V1 consumer already provides one.
