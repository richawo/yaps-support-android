# Conformance fixtures

Every SDK decodes each fixture in its unit tests and checks:

- every `message-*.json` decodes; unknown fields (`futureField`) are ignored
- `message-unknown-card.json` renders as plain text (unknown card type)
- `message-agent-translated.json` shows the Spanish translation when the customer language is `es`, the original otherwise
- every line of `server-frames.jsonl` decodes or, for unknown `t`, is skipped without error
- `push-apns.json` / `push-fcm-data.json` are recognised as Support pushes; any payload without the `support` key is not
- `appearance.json` decodes, and missing fields fall back to local settings then SDK defaults
- `message-app-card.json` renders an app promo (icon, title, body, "Get it"); with `installed: true` it shows "You already have it"
- `message-code-card.json` renders the code with Copy and Redeem; `message-code-card-other-platform.json` shows Copy and a "Redeem on iPhone" hint, no Redeem button
- `message-link-card.json` renders the text with a "Read the guide" button that opens the URL; `message-link-card-screen.json` and `message-link-card-settings.json` render an "Edit profile" / "Open settings" button (the screen card is unavailable unless the host registered `edit_profile` and set `onScreen`; the settings card is unavailable on the web)
- `message-text-link.json` makes `https://wakely.app/help/alarms` tappable (without the trailing comma) and leaves `help@wakely.app` as plain text
