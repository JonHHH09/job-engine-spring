DO $$
DECLARE
    duplicate_groups integer;
BEGIN
    WITH prepared_links AS (
        SELECT id,
               profile_id,
               lower(btrim(link_type)) AS link_type,
               CASE
                   WHEN btrim(url) ~* '^https?://'
                       THEN regexp_replace(btrim(url), '[.,;]+$', '')
                   ELSE 'https://' || regexp_replace(btrim(url), '[.,;]+$', '')
               END AS prepared_url
        FROM profile.profile_links
    ),
    canonical_links AS (
        SELECT id,
               profile_id,
               link_type,
               regexp_replace(
                   regexp_replace(
                       regexp_replace(
                           lower(regexp_replace(prepared_url, '^http://', 'https://', 'i')),
                           '[?#].*$', ''
                       ),
                       ':(80|443)(/|$)', '\2'
                   ),
                   '/+$', ''
               ) AS canonical_url
        FROM prepared_links
    )
    SELECT count(*) INTO duplicate_groups
    FROM (
        SELECT profile_id, link_type, canonical_url
        FROM canonical_links
        GROUP BY profile_id, link_type, canonical_url
        HAVING count(*) > 1
    ) duplicates;

    IF duplicate_groups > 0 THEN
        RAISE EXCEPTION
            'V21 blocked: % canonical profile link conflict group(s). Clean/merge rows before applying migration.',
            duplicate_groups;
    END IF;
END $$;

WITH prepared_links AS (
    SELECT id,
           CASE
               WHEN btrim(url) ~* '^https?://'
                   THEN regexp_replace(btrim(url), '[.,;]+$', '')
               ELSE 'https://' || regexp_replace(btrim(url), '[.,;]+$', '')
           END AS prepared_url
    FROM profile.profile_links
),
canonical_links AS (
    SELECT id,
           regexp_replace(
               regexp_replace(
                   regexp_replace(
                       lower(regexp_replace(prepared_url, '^http://', 'https://', 'i')),
                       '[?#].*$', ''
                   ),
                   ':(80|443)(/|$)', '\2'
               ),
               '/+$', ''
           ) AS canonical_url
    FROM prepared_links
)
UPDATE profile.profile_links links
SET url = canonical_links.canonical_url
FROM canonical_links
WHERE links.id = canonical_links.id
  AND links.url <> canonical_links.canonical_url;
