# AI Pipeline

The AI pipeline is part of the Java backend. There is no separate AI server, model download or GPU:
the backend calls hosted models over HTTPS. It only needs `GROQ_API_KEY` and `GROQ_MODEL`
(see [CONFIGURATION.md](CONFIGURATION.md)).

All paths below are inside `backend/src/main/java/com/jobfinder/`.

## Overview

```
CV (PDF) --PDFBox--> text --Groq--> education, experience, projects, tags --review--> database
                                                                                       |
  --> job list --> Tier 0 rules --> Tier 1 AI gate --> results          |
                                                                          |            |
                                                            tracked job --+------------+
                                                                          v
                                   keywords --> record selection --> AI rewriting
                                                --> HTML template --> Chromium --> PDF
```

## 1. CV parsing

Files: `service/CvService.java`, `service/CvTextExtractor.java`

1. PDFBox extracts the text in memory. The PDF is not saved.
2. The text and the list of allowed skill tags are sent to Groq with temperature 0, asking for strict
   JSON. If that request fails, it is retried without JSON mode.
3. The answer is cleaned: unknown tags, degree levels and experience types are replaced by safe
   values, and keywords are normalised (for example `reactjs` becomes `react`).
4. Email, phone and profile links are found with regular expressions.
5. The user reviews the preview. Only the confirmed data is stored, in one transaction.

Scanned (image only) PDFs are rejected because there is no OCR.

## 2. Job search and filtering

Files: `service/JobSearchService.java`, `service/JobFilterService.java`, `service/JobGateService.java`

Results from JSearch and Adzuna are merged and duplicates removed.

**Tier 0 (rules, no AI cost)**

| Check | Result |
|---|---|
| Posted more than 7 days ago | reject |
| Explicit region restriction (for users outside US, GB, CA, AU, DE, SG) | reject |
| Required experience exceeds the user's by more than 4 years | reject |
| At least 20% of the user's skills appear in the job | accept |
| Otherwise | inconclusive |

**Tier 1 (AI gate)** evaluates inconclusive jobs. The budget is 8 minus the number already accepted.
Groq is tried first, then HuggingFace, then a keyword heuristic, so a model outage never breaks the
search. If the user has no skills stored, inconclusive jobs are shown without AI evaluation.

## 3. CV and cover letter generation

Files: `service/DocumentGenerator.java`, `service/CvContent.java`, `service/DocumentService.java`

1. **Keywords:** Groq extracts up to 16 keywords from the job (fallback: words from the posting).
2. **Selection:** each experience and project scores 3 points for a matching tag and 1 per matching
   keyword. A CV uses up to 3 experiences and 4 projects with a score of 2 or more; if none
   qualifies, the best one is used. A cover letter uses one of each.
3. **Writing:** Groq rewrites experience sentences and project bullets (temperature 0.25), or writes
   the five cover letter paragraphs. If a call fails, stored text or a fixed template is used.
4. **Rendering:** Pebble fills the HTML templates in `backend/src/main/resources/templates/`.
5. **PDF:** after the user edits the HTML, Playwright prints it with headless Chromium
   (`service/PdfService.java`).

## Model settings

File: `client/GroqClient.java`. For `openai/gpt-oss-*` models the client requests low reasoning
effort and adds 1024 tokens of headroom, because reasoning counts against the answer limit.

## Is the AI really running?

If Groq fails, documents are still produced with fallback text. Look in the backend window for
`Groq request for model ... failed with HTTP ...`. Usual causes are a wrong key, an unavailable
`GROQ_MODEL`, or the rate limit.

## Limitations

- AI text can be wrong or invented. Review every document before sending it.
- CV text, profile details and job descriptions are sent to the AI provider.
- The HuggingFace fallback was not tested in the Java version.
