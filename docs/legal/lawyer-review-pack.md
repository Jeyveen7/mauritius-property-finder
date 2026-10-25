# Lawyer review pack

A lawyer's review is the one item here that may cost money. This pack keeps it short: each question can be answered yes or no, or with a corrected sentence. Lower-cost routes worth asking about first:
- A notary or lawyer already working with a partner agency, who may review it as part of the partnership.
- The Economic Development Board (EDB) for the foreign-buyer rules. It publishes the official scheme rules and answers questions about them.
- The Data Protection Office for the privacy questions. It publishes guidance and answers enquiries.

## 1. Foreign-buyer wording

The app shows one of these answers per listing. The rule values live in `backend/seed/rules_2026-08-12.json` (Finance Act 2026, assented 12 Aug 2026). After review, record the reviewer in `rules_version.legal_reviewed_by`.

| Code | English text shown | Question for the lawyer |
|---|---|---|
| open_scheme | "Yes, it is sold under the {PDS / Smart City / IRS / RES / IHS} scheme." | Are these the schemes open to non-citizens today? Any missing? |
| g2_apartment | "Yes, under the G+2 apartment rule." Threshold Rs 6,000,000; building at least 2 floors above ground. | Correct threshold and floor rule? |
| g2_floors_unknown | "Possibly: the listing doesn't say how many floors the building has. Ask the seller." | Acceptable to say "possibly"? |
| g2_below_min_price | "No: G+2 apartments must cost at least Rs 6M." | Correct? |
| citizens_and_permit_holders | "Not as a general rule. Holders of an Occupation, Residence or Retired permit can buy one home of USD 500k or more, with approval through the EDB." | Correct conditions and amount? |
| residency | "At this price the purchase also qualifies for a residence permit." Threshold USD 375,000. | Correct, and for which schemes? |
| footer | "Rules: {Finance Act label}. Confirm with a notary before you commit." | Is this disclaimer enough? |

The French versions are in `android/app/src/main/res/values-fr/strings.xml` and `backend/web/i18n.js`.

## 2. Content and sources

1. Partner terms (`partner-terms.md`): is the licence partners grant us enough to show their listings and photos?
2. Showing facts (price, size, location) with a link back, from a site whose terms allow it: any database-right or copyright concern under Mauritian law?
3. User-shared posts: the poster confirms they took the photos; facts are re-typed and confirmed by the user; no names or phone numbers are copied. Is this enough? Should a takedown address be published?
4. Wikimedia Commons area photos are credited with author and licence, labelled "not of this property", and only licences allowing commercial reuse are kept. Any issue?

## 3. Consumer statements

1. Cost estimate: "An estimate at an assumed 6.0% interest rate, not a loan offer." Enough under consumer-credit rules?
2. Price check: "x% below the median price per m²" for 5 or more comparable asking prices. Any rule on presenting price comparisons?
3. Unverified posts are labelled and carry a warning to ask for the title deed and a surveyor's plan. Is any further duty owed?
