CREATE OR REPLACE FUNCTION job_schema.scrub_safe_display_url(raw_value text)
RETURNS text
LANGUAGE plpgsql
AS $$
DECLARE
    trimmed text;
    without_fragment text;
    without_query text;
BEGIN
    IF raw_value IS NULL OR btrim(raw_value) = '' THEN
        RETURN raw_value;
    END IF;

    trimmed := btrim(raw_value);
    without_fragment := regexp_replace(trimmed, '#.*$', '');
    without_query := regexp_replace(without_fragment, '\?.*$', '');

    RETURN regexp_replace(without_query, '^(https?://)[^/?#]*@', '\1', 'i');
END;
$$;

CREATE OR REPLACE FUNCTION job_schema.scrub_canonical_job_url(raw_value text)
RETURNS text
LANGUAGE plpgsql
AS $$
DECLARE
    trimmed text;
    without_fragment text;
    without_userinfo text;
    base_url text;
    raw_query text;
    safe_query text;
BEGIN
    IF raw_value IS NULL OR btrim(raw_value) = '' THEN
        RETURN raw_value;
    END IF;

    trimmed := btrim(raw_value);
    without_fragment := regexp_replace(trimmed, '#.*$', '');
    without_userinfo := regexp_replace(without_fragment, '^(https?://)[^/?#]*@', '\1', 'i');
    base_url := regexp_replace(without_userinfo, '\?.*$', '');
    raw_query := substring(without_userinfo from '\?(.*)$');

    IF raw_query IS NULL OR raw_query = '' THEN
        RETURN base_url;
    END IF;

    SELECT string_agg(
            lower(split_part(pair, '=', 1)) || '=' || substring(pair from position('=' in pair) + 1),
            '&' ORDER BY lower(split_part(pair, '=', 1)), substring(pair from position('=' in pair) + 1)
    )
    INTO safe_query
    FROM regexp_split_to_table(raw_query, '&') AS pair
    WHERE position('=' in pair) > 1
      AND substring(pair from position('=' in pair) + 1) <> ''
      AND lower(split_part(pair, '=', 1)) IN (
          'gh_jid', 'gh_src', 'jk', 'jobid', 'job_id', 'jid',
          'posting_id', 'postingid', 'reqid', 'req_id', 'vacancyid'
      );

    RETURN CASE WHEN safe_query IS NULL THEN base_url ELSE base_url || '?' || safe_query END;
END;
$$;

DO $$
DECLARE
    conflicting_identity_groups integer;
BEGIN
    SELECT count(*)
    INTO conflicting_identity_groups
    FROM (
        SELECT job_schema.scrub_canonical_job_url(normalized_url)
        FROM job_schema.job_link_ingestions
        GROUP BY job_schema.scrub_canonical_job_url(normalized_url)
        HAVING count(*) > 1
    ) conflicts;

    IF conflicting_identity_groups > 0 THEN
        RAISE EXCEPTION
            'V13 blocked: % canonical job URL conflict group(s). Merge duplicate link jobs before retrying.',
            conflicting_identity_groups;
    END IF;
END $$;

UPDATE job_schema.job_link_ingestions
SET url = job_schema.scrub_safe_display_url(url),
    normalized_url = job_schema.scrub_canonical_job_url(normalized_url);

UPDATE job_schema.job_analysis_runs
SET original_url = job_schema.scrub_safe_display_url(original_url),
    normalized_url = job_schema.scrub_canonical_job_url(normalized_url);

UPDATE job_schema.job_analysis_runs
SET input_json = jsonb_strip_nulls(
        input_json
        || jsonb_build_object(
            'originalUrl', to_jsonb(original_url),
            'normalizedUrl', to_jsonb(normalized_url)
        )
    )
WHERE input_json ? 'originalUrl'
   OR input_json ? 'normalizedUrl';

ALTER TABLE job_schema.job_link_ingestions
    ADD CONSTRAINT job_link_ingestions_url_safe_display
        CHECK (
            position('?' in url) = 0
            AND position('#' in url) = 0
            AND url !~* '^https?://[^/?#]*@'
        ) NOT VALID,
    ADD CONSTRAINT job_link_ingestions_normalized_url_safe_identity
        CHECK (
            position('#' in normalized_url) = 0
            AND normalized_url !~* '^https?://[^/?#]*@'
        ) NOT VALID;

ALTER TABLE job_schema.job_analysis_runs
    ADD CONSTRAINT job_analysis_runs_original_url_safe_display
        CHECK (
            original_url IS NULL
            OR (
                position('?' in original_url) = 0
                AND position('#' in original_url) = 0
                AND original_url !~* '^https?://[^/?#]*@'
            )
        ) NOT VALID,
    ADD CONSTRAINT job_analysis_runs_normalized_url_safe_identity
        CHECK (
            normalized_url IS NULL
            OR (
                position('#' in normalized_url) = 0
                AND normalized_url !~* '^https?://[^/?#]*@'
            )
        ) NOT VALID;

ALTER TABLE job_schema.job_link_ingestions
    VALIDATE CONSTRAINT job_link_ingestions_url_safe_display;

ALTER TABLE job_schema.job_link_ingestions
    VALIDATE CONSTRAINT job_link_ingestions_normalized_url_safe_identity;

ALTER TABLE job_schema.job_analysis_runs
    VALIDATE CONSTRAINT job_analysis_runs_original_url_safe_display;

ALTER TABLE job_schema.job_analysis_runs
    VALIDATE CONSTRAINT job_analysis_runs_normalized_url_safe_identity;

DROP FUNCTION job_schema.scrub_safe_display_url(text);
DROP FUNCTION job_schema.scrub_canonical_job_url(text);
