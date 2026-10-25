-- Photos, partner self-service feeds, crawl settings and free account verification.

-- A photo of a listing. Only three kinds are ever stored or shown:
--   feed_licensed: from a partner whose agreement licenses its images
--   user_upload:   taken by the user, who confirmed they own the rights
-- Area photos (Wikimedia Commons) live in area_photo and are never shown as the property.
CREATE TABLE photo (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  kind           text NOT NULL CHECK (kind IN ('feed_licensed', 'user_upload')),
  listing_id     uuid REFERENCES listing ON DELETE CASCADE,
  user_post_id   uuid REFERENCES user_post ON DELETE CASCADE,
  uploader_id    uuid REFERENCES app_user ON DELETE SET NULL,
  -- Uploads are stored by us (path under the media folder); licensed feed photos stay remote.
  storage_path   text,
  remote_url     text,
  width          int,
  height         int,
  phash          text,
  rights_confirmed_at timestamptz,
  status         text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'hidden')),
  created_at     timestamptz NOT NULL DEFAULT now(),
  CHECK (storage_path IS NOT NULL OR remote_url IS NOT NULL),
  CHECK (kind <> 'user_upload' OR rights_confirmed_at IS NOT NULL)
);
CREATE INDEX photo_listing ON photo (listing_id) WHERE status = 'active';
CREATE INDEX photo_post ON photo (user_post_id);

-- Freely licensed photos taken near a locality, with the credit their licence requires.
CREATE TABLE area_photo (
  id          bigserial PRIMARY KEY,
  locality_id int NOT NULL REFERENCES locality ON DELETE CASCADE,
  source      text NOT NULL DEFAULT 'wikimedia_commons',
  title       text NOT NULL,
  thumb_url   text NOT NULL,
  page_url    text NOT NULL,
  author      text NOT NULL,
  license     text NOT NULL,
  license_url text,
  fetched_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE (locality_id, title)
);

-- Partners (agencies, developers, private sellers with many listings) push their own
-- inventory. They own the content and agree to the partner terms when the key is issued.
ALTER TABLE source ADD COLUMN api_key_sha256 text UNIQUE;
ALTER TABLE source ADD COLUMN contact_email text;
ALTER TABLE source ADD COLUMN partner_terms_accepted_at timestamptz;

-- Crawled sources: nothing is fetched until a person has read the site's terms and
-- recorded that they allow it.
ALTER TABLE source ADD COLUMN crawl_start_urls text[] NOT NULL DEFAULT '{}';
ALTER TABLE source ADD COLUMN terms_reviewed_at timestamptz;
ALTER TABLE source ADD COLUMN terms_reviewed_by text;
ALTER TABLE source ADD COLUMN terms_note text;
ALTER TABLE source ADD COLUMN last_run_at timestamptz;
ALTER TABLE source ADD COLUMN last_run_report jsonb;

-- Free verification through Google sign-in, alongside phone verification.
ALTER TABLE app_user ADD COLUMN google_sub text UNIQUE;
ALTER TABLE app_user ADD COLUMN email text;
ALTER TABLE app_user ADD COLUMN verified_at timestamptz;
UPDATE app_user SET verified_at = phone_verified_at WHERE phone_verified_at IS NOT NULL;
