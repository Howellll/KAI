# KAI

[Русский](README.md) · **English**


**A keyboard with AI support, adaptive key touch zones and a focus on privacy**

Comfortable keyboards send what you type to the cloud. Private ones are honest, but typing on them
hurts: missed keys, clumsy corrections, dictionaries that don't know word forms. KAI is an attempt to
stop choosing.

It is a fork of the [Klava](https://github.com/doroshenkosa/klava) keyboard. 

## AI

- **“AI” section in settings** (off by default): OpenAI-compatible API address and key (stored encrypted
  with the same mechanism as learned words), shared by all models; up to 6 models with their own display
  names, temperature, and a model catalog fetched from the server.
- **AI actions:** three built-in ones — “Fix spelling”, “Translate”, “Edit” — with editable prompts
  (placeholders `{{text}}`, `{{instruction}}`, `{{target_lang}}`) and up to 5 custom ones. An action can be
  bound to a specific model. In the AI form on the keyboard, the actions of the models can be shown in two
  ways: two columns with the actions of two models per tab, or a single column with shortcuts along the
  side edges. The result is shown in a preview with the changes highlighted (optional) and goes into the
  field with the “Insert” or “Replace” button.
- **An “AI” button** (“ИИ” on the Russian layout) replaces the emoji-search button in the suggestion
  strip (only while AI is on). It is on the right by default and the side is configurable; in one-handed
  mode it sits on the side the keys are shifted to. In password fields, the safe and incognito mode the
  button is hidden.
- **The internet permission** is present but used only for AI requests (a single exit point —
  `data/ai/OpenAiCompatibleClient.kt`). A request goes only to the address from settings, only over https,
  and only after you press the button; there are no redirects, automatic retries, or logging of the text or
  the key. There is no telemetry, analytics or crash reporting. The network-state permission is not
  requested: “no network” is detected from the failed request.

## Private. No “but”

- **Network only for AI, and only on your action.** While AI is off (the default) the keyboard sends nothing.
  When it is on, text goes to the server you specified only after you press “AI” and choose an action. There are
  no automatic or background requests. Use only an API you trust.
- **No ads, no analytics.** All the code is in this repository for anyone to read.
- **Everything it learns stays on your phone — encrypted.** Learned words, pinned clipboard entries and
  recent emoji are encrypted (AES-256) with a key from the Android keystore. On phones with a hardware-backed
  keystore (nearly all modern ones) the key can't be extracted, so files copied off the phone can't be read.
  Moving what it learned to another phone is up to you — with a password-protected file. Nothing goes into
  cloud backups. It doesn't protect against someone in full control of the unlocked phone.
- **In password fields and incognito mode** it remembers nothing.
- **Clipboard history is off by default.** When enabled, text and images live in memory only, for one
  hour (or what you choose, from 5 minutes to a day), and anything copied with the "sensitive" flag
  (password managers set it on Android 13 and later) is never stored. Only what you pin 📌 yourself is kept on the phone.
- **A safe for passwords and codes.** A separate folder in the clipboard: entries are kept indefinitely,
  encrypted, and open only with a PIN or fingerprint; it locks as soon as the keyboard closes. Copied
  text goes into the safe in one tap, even while it's locked — but reading needs the PIN. The safe
  screen in the app is behind the PIN too and stays out of screenshots. A "wipe PIN" silently erases the entries and
  opens an empty safe if you're ever forced to open it. After 5 wrong PINs, delays kick in (30 seconds up
  to 8 hours) that changing the system clock does not skip. If you forget the PIN, the safe can be
  deleted, but not sooner than an hour after the request.

## Helps you type instead of getting in the way

One of the keyboard's main tricks is invisible. The keys look normal, but **the touch area of each letter
quietly grows or shrinks** depending on how likely it is to come next. Type «кузнец» (blacksmith) and land
between «ы» and «в» — you get «кузнецы», because «кузнецв» isn't a word. The mistake isn't fixed
afterwards; it just doesn't happen.

And it **adapts to you**:

- **it learns how you miss** — thumbs usually land a bit low and to the side, so key centers
  quietly shift to where *you* actually tap;
- **it learns your words** — slang, names, pet phrases — and which words you write together,
  so the right letters grow more precisely every day.

It never argues with you: the center of each key always belongs to that key, the tap right after ⌫
goes exactly where you touched, and in unfamiliar words the predictions barely interfere.

In the typing simulator (a model finger with natural scatter, the same phrases), plain keys produce
a wrong letter every 7 taps, smart zones — every 18: **2.5× fewer misses**.

## What else it does

- Russian and English; ~870,000 Russian word forms, with frequencies from chats, subtitles, the web and news, so it knows not only «кузнец» but also «кузнецами» and «кузнецах».
- Swipe typing, next-word suggestions.
- Autocorrect with adjustable strength (0–100); ⌫ right after undoes it.
- **Letter order in fast typing:** two thumbs hit almost at once and the letters came in swapped
  («пр**ви**ет») — the keyboard figures out which one you meant first, right away.
- **Wrong-layout fix:** “ghbdtn” → “привет”, “руддщ” → “hello”, and the language switches by itself.
- Move the cursor by sliding on the space bar; switch language with 🌐 or, optionally, a flick on space.
- Two symbol styles — pick the one you're used to by its picture.
- Likely-letter highlight: the keys you will probably press next glow faintly in the accent color, so they are easier to hit.
- Four themes — pure black (AMOLED), dark, light and “System” (Material You) — and any accent color.
- Emoji: search by name and keywords (Russian word forms included), emoji suggestions for the word you type, skin tones.
- One-handed mode, adjustable keyboard height and key gap.
- Search across settings.
- Move to another phone with a file: settings (including the AI model list), and optionally learned
  words — then the file is password-encrypted.

## Install

Requires Android 8.0 or newer.

1. Download `KAI.apk` from the [latest release](../../releases/latest).
2. Open it on your phone. Android will ask to allow installs from this source — allow it.
3. Open KAI and follow the two on-screen steps: enable the keyboard and select it.

Want to build it yourself? See [BUILDING.md](BUILDING.md).

## Afterword

KAI is a niche keyboard. I took another niche, but in my opinion quite good, keyboard, [Klava](https://github.com/doroshenkosa/klava), as a base and adapted it to my needs. It has only two layouts, RU and EN, and the AI requires an OpenAI-compatible API, so this is definitely not a mass-market product. But perhaps someone else will find it interesting.

## License

Code — [GNU GPL 3.0](LICENSE) or later; KAI is a derivative work of Klava and the original copyright is
kept in [NOTICE](NOTICE). It is a free license with one condition: whoever builds Klava's or KAI's
code into their app must publish the source of the whole app under the same license. The keyboard stays
open for people and does not end up inside someone else's closed product.

Dictionaries, statistics and emoji data are not code and have their own licenses
(CC BY-SA 4.0, CC BY 2.0 FR, CC BY and Unicode License v3); sources and authors are listed in [DATA_LICENSES.md](DATA_LICENSES.md)
and [NOTICE](NOTICE).

