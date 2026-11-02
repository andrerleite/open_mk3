# Contributing to Open MK3

Thanks for helping! A few ground rules keep the project healthy and legally clean.

## Content and IP

- Contribute only code and content you wrote or have the right to share under GPL-3.0.
- Don't add samples, presets, artwork, fonts or text taken from commercial products or
  their manuals.
- Workflow behaviour should follow the conventions in [docs/controls.md](docs/controls.md).
  Describe it in your own words.
- Third-party code must have a GPL-compatible licence (MIT, BSD, Apache-2.0, GPL-3).
  - Keep its original licence header.
  - Add it to `THIRD_PARTY_NOTICES.md`.

## Code

- **`core/`** is shared by both apps. Keep the real-time audio path (`engine.cpp`
  render/pump) free of allocations, locks and blocking calls.
- **`vanilla/`** stays minimal. New features normally go to the full app. Shared building
  blocks go to `core/`.
- **Behaviour changes on the controller:**
  - Update the status column in `docs/controls.md`.
  - Describe how you tested it on hardware (logs from the phone app's *Share log* help).
- **Privacy:** don't commit logs, device serial numbers or personal paths.

## Pull requests

CI builds both apps for every pull request. Release signing never runs for forks.
