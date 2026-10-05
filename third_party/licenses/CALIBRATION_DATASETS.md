# Calibration provenance and dataset attribution

The calibration JSON was fitted locally on 4,415 labeled question examples for
the pinned multilingual checkpoint and S256 builder. Raw calibration texts are
not distributed here. The included invented gate fixtures are not this corpus.
The JSON retains the original conversion provenance and hashes; historical
`results/...` and `LICENSE_CHECK.md` fields name the conversion's evidence, not
additional files in this package. This document carries the relevant attribution.

| Dataset / authors or curators | Config and source split | Question examples / unique source rows | Applied source terms | Pinned source |
|---|---|---:|---|---|
| Civil Comments / Jigsaw | default, validation, EN | 1,200 / 800 | [CC0-1.0](https://creativecommons.org/publicdomain/zero/1.0/) | [google/civil_comments at f2970eb](https://huggingface.co/datasets/google/civil_comments/blob/f2970eb3a55777454c94069077cc8d9b5866312d/README.md) |
| MASSIVE / Amazon MASSIVE authors | en-US, test | 870 / 731 | [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) | [AmazonScience/massive at ff6bd8e](https://huggingface.co/datasets/AmazonScience/massive/blob/ff6bd8e4b27c3543e4f8fe2108f32bb95a6f8740/README.md) |
| MASSIVE / Amazon MASSIVE authors | ja-JP, test | 870 / 733 | CC BY 4.0 | Same pinned MASSIVE card |
| JGLUE authors and contributors | JNLI, validation, JA | 650 / 556 | [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) | [shunk031/JGLUE at 41cc99f](https://huggingface.co/datasets/shunk031/JGLUE/blob/41cc99f1c01d41b1ab13435ae97b7076c2199f4c/README.md) |
| JGLUE authors and contributors | JCommonsenseQA, validation, JA | 200 / 200 | CC BY-SA 4.0 | Same pinned JGLUE card |
| JGLUE authors and contributors | JSTS, validation, JA | 400 / 400 | CC BY-SA 4.0 | Same pinned JGLUE card; validation v1.2.0 |
| RONDHUIT / Livedoor corpus authors; JMTEB curators | livedoor_news, test, JA | 225 / 225 | [CC BY-ND 2.1 JP](https://creativecommons.org/licenses/by-nd/2.1/jp/deed.en) | [sbintuitions/JMTEB at 6064d6d](https://huggingface.co/datasets/sbintuitions/JMTEB/blob/6064d6d00e3eccca0f9673621022b409f79d1a18/README.md) |

Applied JGLUE terms are CC BY-SA 4.0, following the original licensing section,
rather than only the shortened Hub tag. The Livedoor subset uses its specific
CC BY-ND terms rather than the aggregate JMTEB label; its source text remains
local and no modified-text dataset is included. The package's Apache-2.0 label
does not change these dataset declarations.

Changes during local calibration: deterministic sampling; model question/prompt
construction; label-to-option index mapping; tokenization and S256 truncation;
fixed local fit/validation assignment; EN/JA balancing of fit rows only; and
temperature fitting. Original source text fields were not rewritten. The Civil
Comments toxicity fraction was mapped to four severity levels as a proxy for the
unchanged rubric. JSTS scores were mapped to three levels at <2, [2,3.5], and >3.5.
Additional binary JNLI questions used entailment/contradiction with neutral excluded.

All original validation memberships were retained. Mixed tasks, translated
utterances and source reuse mean the local validation is not an independent
generalization benchmark. In particular, 75 JNLI pairs are reused across tasks,
including 9 new fit rows whose source pairs also occur in old validation tasks.
See the calibration table and limits in the model card and the JSON provenance.

Excluded from fitting: the inspected English/Japanese multilingual-sentiments
subsets, MARC-ja, JMTEB Amazon review classification and WRIME classification.
No source texts from these excluded subsets are distributed.
