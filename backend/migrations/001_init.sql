-- Core schema. One real-world place is a `property`; each advert of it on one
-- source is a `listing`; every fetch of a listing is a `snapshot`.
-- Money is stored as integer minor units (cents) plus the advertised currency.

CREATE EXTENSION IF NOT EXISTS unaccent;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- unaccent() is only STABLE; wrap it so it can be used in indexes.
CREATE OR REPLACE FUNCTION f_unaccent(text) RETURNS text
  LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
  AS $$ SELECT public.unaccent('public.unaccent'::regdictionary, $1) $$;

CREATE TABLE locality (
  id          serial PRIMARY KEY,
  name        text NOT NULL UNIQUE,
  district    text NOT NULL,
  region      text NOT NULL CHECK (region IN ('North', 'West', 'Centre', 'East', 'South')),
  lat         double precision NOT NULL,
  lon         double precision NOT NULL,
  aliases     text[] NOT NULL DEFAULT '{}',
  -- name, district and aliases, lower-cased and unaccented; filled by the loader.
  search_text text NOT NULL
);
CREATE INDEX locality_search_trgm ON locality USING gin (search_text gin_trgm_ops);

CREATE TABLE source (
  id              text PRIMARY KEY,
  name            text NOT NULL,
  kind            text NOT NULL CHECK (kind IN ('partner_feed', 'crawl', 'user_post')),
  -- Thumbnails may only be shown when the partner agreement licenses them.
  images_licensed boolean NOT NULL DEFAULT false,
  active          boolean NOT NULL DEFAULT true,
  created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE rules_version (
  id                serial PRIMARY KEY,
  effective_from    date NOT NULL UNIQUE,
  label             text NOT NULL,
  rules             jsonb NOT NULL,
  legal_reviewed_by text,
  created_at        timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE fx_rate (
  day          date NOT NULL,
  currency     char(3) NOT NULL CHECK (currency IN ('EUR', 'USD')),
  mur_per_unit numeric(12, 6) NOT NULL CHECK (mur_per_unit > 0),
  source       text NOT NULL,
  PRIMARY KEY (day, currency)
);

CREATE TABLE setting (
  key        text PRIMARY KEY,
  value      jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE property (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  type        text NOT NULL CHECK (type IN ('land', 'apartment', 'house')),
  locality_id int REFERENCES locality,
  lat         double precision NOT NULL,
  lon         double precision NOT NULL,
  status      text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'gone', 'hidden')),
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX property_active_geo ON property (lat, lon) WHERE status = 'active';

CREATE TABLE listing (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  source_id          text NOT NULL REFERENCES source,
  source_ref         text NOT NULL,
  url                text,
  property_id        uuid REFERENCES property,
  type               text NOT NULL CHECK (type IN ('land', 'apartment', 'house')),
  title              text NOT NULL,
  locality_id        int REFERENCES locality,
  lat                double precision NOT NULL,
  lon                double precision NOT NULL,
  geo_precision      text NOT NULL CHECK (geo_precision IN ('exact', 'locality')),
  price_minor        bigint NOT NULL CHECK (price_minor > 0),
  currency           char(3) NOT NULL CHECK (currency IN ('MUR', 'EUR', 'USD')),
  -- Advertised price converted at the latest FX rate; refreshed by `mpf refresh-fx`.
  price_mur_minor    bigint NOT NULL,
  negotiable         boolean NOT NULL DEFAULT false,
  plot_m2            numeric(12, 2),
  floor_m2           numeric(12, 2),
  bedrooms           smallint,
  scheme             text CHECK (scheme IN ('PDS', 'IRS', 'RES', 'SMART_CITY', 'IHS', 'G+2')),
  floors_above_ground smallint,
  coastal            boolean NOT NULL DEFAULT false,
  agency_name        text,
  contact_url        text,
  image_urls         text[] NOT NULL DEFAULT '{}',
  image_hashes       text[] NOT NULL DEFAULT '{}',
  verified           boolean NOT NULL DEFAULT true,
  -- Result of the foreign-buyer rules for this listing; recomputed on rule changes.
  eligibility        jsonb NOT NULL,
  foreign_eligible   boolean GENERATED ALWAYS AS ((eligibility ->> 'status') = 'yes') STORED,
  content_hash       text NOT NULL,
  status             text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'gone', 'hidden')),
  missed_checks      int NOT NULL DEFAULT 0,
  first_seen_at      timestamptz NOT NULL DEFAULT now(),
  last_seen_at       timestamptz NOT NULL DEFAULT now(),
  last_checked_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (source_id, source_ref),
  CHECK (plot_m2 IS NOT NULL OR floor_m2 IS NOT NULL)
);
CREATE INDEX listing_property ON listing (property_id);
CREATE INDEX listing_active_geo ON listing (lat, lon) WHERE status = 'active';

CREATE TABLE snapshot (
  id           bigserial PRIMARY KEY,
  listing_id   uuid NOT NULL REFERENCES listing ON DELETE CASCADE,
  fetched_at   timestamptz NOT NULL DEFAULT now(),
  price_minor  bigint NOT NULL,
  currency     char(3) NOT NULL,
  status       text NOT NULL,
  content_hash text NOT NULL
);
CREATE INDEX snapshot_listing ON snapshot (listing_id, fetched_at);

CREATE TABLE price_event (
  id              bigserial PRIMARY KEY,
  listing_id      uuid NOT NULL REFERENCES listing ON DELETE CASCADE,
  at              timestamptz NOT NULL DEFAULT now(),
  old_price_minor bigint NOT NULL,
  new_price_minor bigint NOT NULL,
  currency        char(3) NOT NULL
);
CREATE INDEX price_event_listing ON price_event (listing_id, at);

CREATE TABLE merge_decision (
  id          bigserial PRIMARY KEY,
  listing_id  uuid NOT NULL REFERENCES listing ON DELETE CASCADE,
  property_id uuid NOT NULL REFERENCES property,
  action      text NOT NULL CHECK (action IN ('merge', 'new', 'split')),
  score       numeric(4, 3),
  decided_by  text NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now(),
  undone_at   timestamptz
);

CREATE TABLE review_item (
  id                    bigserial PRIMARY KEY,
  listing_id            uuid NOT NULL REFERENCES listing ON DELETE CASCADE,
  candidate_property_id uuid NOT NULL REFERENCES property,
  score                 numeric(4, 3) NOT NULL,
  status                text NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'merged', 'separate')),
  created_at            timestamptz NOT NULL DEFAULT now(),
  resolved_at           timestamptz,
  resolved_by           text,
  UNIQUE (listing_id, candidate_property_id)
);

CREATE TABLE app_user (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  locale            text NOT NULL DEFAULT 'en' CHECK (locale IN ('en', 'fr')),
  phone_e164        text UNIQUE,
  phone_verified_at timestamptz,
  push_token        text,
  created_at        timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE auth_token (
  token_sha256 text PRIMARY KEY,
  user_id      uuid NOT NULL REFERENCES app_user ON DELETE CASCADE,
  created_at   timestamptz NOT NULL DEFAULT now(),
  last_used_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE phone_otp (
  user_id    uuid PRIMARY KEY REFERENCES app_user ON DELETE CASCADE,
  phone_e164 text NOT NULL,
  code_sha256 text NOT NULL,
  attempts   int NOT NULL DEFAULT 0,
  expires_at timestamptz NOT NULL
);

CREATE TABLE saved_search (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id         uuid NOT NULL REFERENCES app_user ON DELETE CASCADE,
  filters         jsonb NOT NULL,
  created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE saved_property (
  user_id     uuid NOT NULL REFERENCES app_user ON DELETE CASCADE,
  property_id uuid NOT NULL REFERENCES property,
  created_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, property_id)
);

CREATE TABLE alert (
  id              bigserial PRIMARY KEY,
  user_id         uuid NOT NULL REFERENCES app_user ON DELETE CASCADE,
  kind            text NOT NULL CHECK (kind IN ('new_match', 'price_drop', 'gone')),
  saved_search_id uuid REFERENCES saved_search ON DELETE CASCADE,
  property_id     uuid NOT NULL REFERENCES property,
  created_at      timestamptz NOT NULL DEFAULT now(),
  sent_at         timestamptz
);
CREATE INDEX alert_unsent ON alert (created_at) WHERE sent_at IS NULL;

CREATE TABLE lead (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     uuid NOT NULL REFERENCES app_user,
  property_id uuid NOT NULL REFERENCES property,
  listing_id  uuid NOT NULL REFERENCES listing,
  source_id   text NOT NULL REFERENCES source,
  channel     text NOT NULL CHECK (channel IN ('source_page', 'phone', 'whatsapp', 'email')),
  created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE report (
  id          bigserial PRIMARY KEY,
  user_id     uuid NOT NULL REFERENCES app_user ON DELETE CASCADE,
  property_id uuid NOT NULL REFERENCES property,
  reason      text NOT NULL CHECK (reason IN ('sold', 'wrong_details', 'scam', 'duplicate', 'other')),
  created_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE (user_id, property_id)
);

CREATE TABLE user_post (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id        uuid NOT NULL REFERENCES app_user ON DELETE CASCADE,
  -- Raw post text is personal data; `mpf purge` clears it after the retention period.
  raw_text       text,
  extracted      jsonb NOT NULL,
  extractor      text NOT NULL,
  status         text NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'published')),
  listing_id     uuid REFERENCES listing,
  created_at     timestamptz NOT NULL DEFAULT now(),
  raw_deleted_at timestamptz
);
CREATE INDEX user_post_user_day ON user_post (user_id, created_at);
