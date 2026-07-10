CREATE OR REPLACE FUNCTION job_schema.decode_job_query_component(raw_value text)
RETURNS text
LANGUAGE plpgsql
IMMUTABLE
STRICT
AS $$
DECLARE
    decoded bytea := ''::bytea;
    position_index integer := 1;
    current_character text;
    encoded_byte text;
BEGIN
    WHILE position_index <= length(raw_value) LOOP
        current_character := substring(raw_value from position_index for 1);
        IF current_character = '+' THEN
            decoded := decoded || decode('20', 'hex');
            position_index := position_index + 1;
        ELSIF current_character = '%'
              AND position_index + 2 <= length(raw_value)
              AND substring(raw_value from position_index + 1 for 2) ~ '^[0-9A-Fa-f]{2}$' THEN
            encoded_byte := substring(raw_value from position_index + 1 for 2);
            decoded := decoded || decode(encoded_byte, 'hex');
            position_index := position_index + 3;
        ELSE
            decoded := decoded || convert_to(current_character, 'UTF8');
            position_index := position_index + 1;
        END IF;
    END LOOP;

    RETURN convert_from(decoded, 'UTF8');
END;
$$;

CREATE OR REPLACE FUNCTION job_schema.encode_job_query_component(raw_value text)
RETURNS text
LANGUAGE plpgsql
IMMUTABLE
STRICT
AS $$
DECLARE
    encoded text := '';
    utf8_bytes bytea := convert_to(raw_value, 'UTF8');
    position_index integer;
    current_byte integer;
BEGIN
    FOR position_index IN 0..length(utf8_bytes) - 1 LOOP
        current_byte := get_byte(utf8_bytes, position_index);
        IF (current_byte BETWEEN 48 AND 57)
           OR (current_byte BETWEEN 65 AND 90)
           OR (current_byte BETWEEN 97 AND 122)
           OR current_byte IN (42, 45, 46, 95) THEN
            encoded := encoded || chr(current_byte);
        ELSIF current_byte = 32 THEN
            encoded := encoded || '%20';
        ELSE
            encoded := encoded || '%' || upper(lpad(to_hex(current_byte), 2, '0'));
        END IF;
    END LOOP;

    RETURN encoded;
END;
$$;

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
    normalized_host text;
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
    normalized_host := lower(substring(base_url from '^https?://([^/:?#]+)'));
    raw_query := substring(without_userinfo from '\?(.*)$');

    IF raw_query IS NULL OR raw_query = '' THEN
        RETURN base_url;
    END IF;

    SELECT string_agg(
            job_schema.encode_job_query_component(lower(decoded_name)) || '='
                || job_schema.encode_job_query_component(decoded_value),
            '&' ORDER BY lower(decoded_name), decoded_value
    )
    INTO safe_query
    FROM regexp_split_to_table(raw_query, '&') AS pair
    CROSS JOIN LATERAL (
        SELECT job_schema.decode_job_query_component(split_part(pair, '=', 1)) AS decoded_name,
               btrim(job_schema.decode_job_query_component(substring(pair from position('=' in pair) + 1))) AS decoded_value
    ) decoded
    WHERE position('=' in pair) > 1
      AND decoded_value <> ''
      AND length(decoded_value) <= 128
      AND decoded_value ~ '^[A-Za-z0-9][A-Za-z0-9._~-]*$'
      AND (
          ((normalized_host = 'indeed.com' OR normalized_host LIKE '%.indeed.com')
              AND lower(decoded_name) = 'jk')
          OR ((normalized_host = 'greenhouse.io' OR normalized_host LIKE '%.greenhouse.io')
              AND lower(decoded_name) = 'gh_jid')
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
DROP FUNCTION job_schema.decode_job_query_component(text);
DROP FUNCTION job_schema.encode_job_query_component(text);
