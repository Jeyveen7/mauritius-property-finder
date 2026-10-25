# Data Protection Office: what to prepare

The Data Protection Act 2017 requires controllers to register with the Data Protection Commissioner. This page gathers what the form asks, so filing takes one sitting. **Check the current form, procedure and any fee on https://dataprotection.govmu.org before filing**: this repository can't confirm them.

## Facts for the form

- **Controller:** [name, address, BRN if a company], contact [email].
- **Purpose of processing:** a property search app that gathers property listings, lets users save searches and receive alerts, and lets verified users share listings they found.
- **Categories of data subjects:** app users; people who post listings; partner agencies' staff contacts.
- **Categories of personal data:** device identifiers; Google account ID and email or phone number (verification only); push tokens; saved searches; posts (text, photos); enquiry and report records. No special categories are processed. No data about children is sought.
- **Recipients and processors:** hosting provider [name, country]; Google LLC (Firebase Cloud Messaging, Google sign-in); OpenFreeMap (map tiles, receives IP addresses).
- **Transfers abroad:** Google processes data outside Mauritius; [hosting country]. State the safeguard you rely on (for Google, its standard data processing terms).
- **Retention:** raw post text 30 days (`mpf purge`, run daily); enquiries and reports 12 months; account data while the account is active.
- **Security measures:** tokens and one-time codes stored only as hashes; HTTPS in production; uploaded photos re-encoded to remove location metadata; admin access by secret token; database not exposed to the internet; daily backups [describe].

## Records the Act expects you to keep

- A record of processing: this page, kept up to date.
- How you handle access, correction and deletion requests: reply within the legal deadline (check the Act), from [email].
- A breach procedure: who decides, how the Office is told, how users are told.

## In the code

- `mpf purge`: deletes raw post text after `MPF_POST_RETENTION_DAYS` (30). Schedule it daily.
- Deleting a user (`DELETE FROM app_user WHERE id = …`) cascades to their tokens, saved items, alerts and posts. Leads and reports keep the user ID for 12 months, then delete them with a scheduled query.
