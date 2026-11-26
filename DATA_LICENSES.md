# Лицензии данных

Код Klava — GNU GPL 3.0 или более поздняя версия (`LICENSE`). Данные ниже — не код, у них свои лицензии.

| Файл | Откуда | Лицензия |
|---|---|---|
| `app/src/main/assets/ru.dict` | Частоты слов — смесь: [FrequencyWords](https://github.com/hermitdave/FrequencyWords) (Hermit Dave, посчитаны по OpenSubtitles 2018), соцсети из корпуса [Taiga](https://tatianashavrina.github.io/taiga_site/) (Т. Шаврина, О. Шаповалова, CC BY-SA 3.0), интернет и новости из [Leipzig Corpora Collection](https://wortschatz.uni-leipzig.de/en/download) (CC BY); формы слов — словарь [OpenCorpora](https://opencorpora.org) через [pymorphy3](https://github.com/no-plagiarism/pymorphy3) | [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) |
| `app/src/main/assets/en.dict` | Частоты слов — [FrequencyWords](https://github.com/hermitdave/FrequencyWords) | [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) |
| `app/src/main/assets/ru.bi` | Статистика пар слов, смесь корпусов: предложения [Tatoeba](https://tatoeba.org), субтитры [OpenSubtitles](http://www.opensubtitles.org) из коллекции [OPUS](https://opus.nlpl.eu/OpenSubtitles/corpus/version/OpenSubtitles), соцсети [Taiga](https://tatianashavrina.github.io/taiga_site/), интернет и новости [Leipzig Corpora Collection](https://wortschatz.uni-leipzig.de/en/download) | [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) (из-за Taiga); см. ниже про OpenSubtitles |
| `app/src/main/assets/en.bi` | Статистика пар слов, поровну: предложения [Tatoeba](https://tatoeba.org) и субтитры OpenSubtitles (OPUS) | [CC BY 2.0 FR](https://creativecommons.org/licenses/by/2.0/fr/) (часть Tatoeba); см. ниже про OpenSubtitles |
| `app/src/main/assets/emoji.txt` | Список эмодзи — [emoji-test.txt](https://unicode.org/Public/emoji/latest/emoji-test.txt), названия и слова для поиска — [CLDR](https://github.com/unicode-org/cldr) (Unicode, Inc.); формы русских слов поиска — словарь [OpenCorpora](https://opencorpora.org) через pymorphy3; список самых частых слов (им эмодзи не предлагаются) — [FrequencyWords](https://github.com/hermitdave/FrequencyWords) | [Unicode License v3](app/src/main/assets/UNICODE-LICENSE.txt); формы слов — [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) |
| `app/src/test/resources/sim_ru.tsv` | Предложения [Tatoeba](https://tatoeba.org); в первой колонке — ID, по нему виден автор: `https://tatoeba.org/sentences/show/<id>` | [CC BY 2.0 FR](https://creativecommons.org/licenses/by/2.0/fr/) |

Изменения: частоты посчитаны по смеси корпусов и отфильтрованы (`tools/build_corpora.py`), к словам добавлены все их формы,
файлы переведены в бинарный формат (`tools/build_dicts.py`); из предложений Tatoeba и субтитров
посчитаны частоты пар слов (`tools/build_bigrams.py`); список эмодзи и слова для поиска собраны
в один файл, к ним добавлены разговорные слова («ахаха», «спасибо») и формы русских слов (`tools/build_emoji.py`).

Из субтитров OpenSubtitles, Taiga и Leipzig в проект попадают только числа — сколько раз встречалось
слово, сколько раз одно слово шло за другим (для 20 000 самых частых слов, не больше 250 продолжений
у каждого) и за парой слов (для ~22 000 самых частых пар); текст корпусов не хранится и из этих чисел не восстанавливается. Taiga: T. Shavrina, O. Shapovalova (2017),
«To the methodology of corpus construction for machine learning: Taiga syntax tree corpus and parser», CORPORA 2017.
Leipzig: D. Goldhahn, T. Eckart, U. Quasthoff (2012), «Building Large Monolingual Dictionaries at the Leipzig
Corpora Collection», LREC 2012. Источник указан так, как просит OPUS: ссылка на opensubtitles.org
и статья P. Lison, J. Tiedemann (2016) — «OpenSubtitles2016: Extracting Large Parallel Corpora from Movie and TV Subtitles», LREC 2016.

Словарь OpenCorpora и корпус Taiga распространяются под CC BY-SA 3.0; производные от них данные
выпускаются под CC BY-SA 4.0, как это разрешает CC BY-SA 3.0 (раздел 4(b)). Тексты Taiga — посты
из открытых соцсетей; в проект из них попадают только числа, ни одного текста.

Исходные списки и корпуса в репозиторий не входят: частотные списки `tools/build_dicts.py` скачивает
сам, корпуса для пар слов — командами из [BUILDING.md](BUILDING.md); данные эмодзи скачивает `tools/build_emoji.py`.
