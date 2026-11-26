# Building KAI · Сборка

## Русский

Нужны JDK 17+ и Android SDK (путь — в `local.properties` или `ANDROID_HOME`).

    ./gradlew assembleRelease     # APK: app/build/outputs/apk/release/app-release.apk
    ./gradlew testDebugUnitTest   # тесты, включая симулятор набора

Готовые словари уже лежат в `app/src/main/assets`. Если хотите пересобрать их сами:

    python3 -m venv venv && venv/bin/pip install pymorphy3 pymorphy3-dicts-ru
    venv/bin/python tools/build_dicts.py              # сам скачает частотные списки

    # пары слов для подсказки следующего слова — смесь Tatoeba и субтитров OpenSubtitles (OPUS)
    curl -LO https://downloads.tatoeba.org/exports/per_language/rus/rus_sentences.tsv.bz2 && bunzip2 rus_sentences.tsv.bz2
    curl -LO https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences.tsv.bz2 && bunzip2 eng_sentences.tsv.bz2
    curl -s https://object.pouta.csc.fi/OPUS-OpenSubtitles/v2018/mono/en.txt.gz | gunzip | head -n 6000000 > en_sub.txt
    python3 tools/build_bigrams.py en eng_sentences.tsv en_sub.txt

    # русский: смесь субтитров, соцсетей Taiga, интернета и новостей Leipzig
    venv/bin/pip install pyarrow
    venv/bin/python tools/build_corpora.py CORP       # команды скачивания — в начале этого скрипта
    venv/bin/python tools/build_dicts.py ru
    venv/bin/python tools/build_bigrams.py ru rus_sentences.tsv=0.15 CORP/ru_sub40.txt=0.30 \
        CORP/social.txt=0.35 CORP/web.txt=0.12 CORP/news.txt=0.08

    # эмодзи: список Unicode и слова для поиска из CLDR (скачает сам), формы слов — pymorphy3
    venv/bin/python tools/build_emoji.py

## English

Requires JDK 17+ and the Android SDK (path in `local.properties` or `ANDROID_HOME`).

    ./gradlew assembleRelease     # APK: app/build/outputs/apk/release/app-release.apk
    ./gradlew testDebugUnitTest   # tests, including the typing simulator

Prebuilt dictionaries are already in `app/src/main/assets`. To rebuild them yourself:

    python3 -m venv venv && venv/bin/pip install pymorphy3 pymorphy3-dicts-ru
    venv/bin/python tools/build_dicts.py              # downloads the frequency lists itself

    # word pairs for next-word suggestions — a mix of Tatoeba and OpenSubtitles (OPUS)
    curl -LO https://downloads.tatoeba.org/exports/per_language/rus/rus_sentences.tsv.bz2 && bunzip2 rus_sentences.tsv.bz2
    curl -LO https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences.tsv.bz2 && bunzip2 eng_sentences.tsv.bz2
    curl -s https://object.pouta.csc.fi/OPUS-OpenSubtitles/v2018/mono/en.txt.gz | gunzip | head -n 6000000 > en_sub.txt
    python3 tools/build_bigrams.py en eng_sentences.tsv en_sub.txt

    # Russian: a mix of subtitles, Taiga social media, Leipzig web and news
    venv/bin/pip install pyarrow
    venv/bin/python tools/build_corpora.py CORP       # download commands are at the top of that script
    venv/bin/python tools/build_dicts.py ru
    venv/bin/python tools/build_bigrams.py ru rus_sentences.tsv=0.15 CORP/ru_sub40.txt=0.30 \
        CORP/social.txt=0.35 CORP/web.txt=0.12 CORP/news.txt=0.08

    # emoji: the Unicode list and CLDR search words (downloaded automatically), word forms via pymorphy3
    venv/bin/python tools/build_emoji.py

## Релизы · Releases

Релизы собирает GitHub Actions (`.github/workflows/release.yml`): тег `v*` → тесты → подписанный
`KAI.apk` в Releases. Один раз нужно создать ключ подписи и положить его в секреты репозитория.

**Ключ храните в надёжном месте и не теряйте:** без него нельзя выпустить обновление —
Android не поставит APK, подписанный другим ключом, поверх уже установленного.

    keytool -genkeypair -keystore klava.jks -alias klava -keyalg RSA -keysize 4096 -validity 36500
    base64 -i klava.jks | pbcopy        # macOS; на Linux: base64 -w0 klava.jks

Секреты (Settings → Secrets and variables → Actions): `KEYSTORE_BASE64` (вывод base64),
`KEYSTORE_PASSWORD`, `KEY_ALIAS` (`klava`), `KEY_PASSWORD` (если отличается от пароля хранилища).

    git tag v1.0 && git push origin v1.0

Версия приложения берётся из тега (`v1.2` → `1.2`). Локально собрать подписанный релиз можно,
указав ключ в `~/.gradle/gradle.properties`: `klava.keystore`, `klava.keystorePassword`,
`klava.keyAlias`, `klava.keyPassword`. Без ключа локальная сборка подписывается debug-ключом.

Releases are built by GitHub Actions on `v*` tags. Create the signing key once (command above),
store it safely — losing it means users can't receive updates — and add the four secrets listed above.
