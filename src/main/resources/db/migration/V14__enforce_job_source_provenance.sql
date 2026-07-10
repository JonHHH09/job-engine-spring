DO $$
DECLARE
    violation_count integer;
BEGIN
    SELECT count(*)
    INTO violation_count
    FROM job_schema.jobs jobs
    LEFT JOIN (
        SELECT job_id, count(*) AS link_count
        FROM job_schema.job_link_ingestions
        GROUP BY job_id
    ) links ON links.job_id = jobs.id
    LEFT JOIN (
        SELECT job_id, count(*) AS text_count
        FROM job_schema.job_text_ingestions
        GROUP BY job_id
    ) texts ON texts.job_id = jobs.id
    WHERE (jobs.source_method = 'link'
            AND (COALESCE(links.link_count, 0) <> 1 OR COALESCE(texts.text_count, 0) <> 0))
       OR (jobs.source_method = 'text'
            AND (COALESCE(texts.text_count, 0) <> 1 OR COALESCE(links.link_count, 0) <> 0));

    IF violation_count > 0 THEN
        RAISE EXCEPTION
            'V14 blocked: existing job provenance violation(s): % job(s) do not match source_method',
            violation_count;
    END IF;
END $$;

CREATE OR REPLACE FUNCTION job_schema.assert_job_source_provenance(target_job_id uuid)
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
    job_source_method text;
    link_count integer;
    text_count integer;
BEGIN
    IF target_job_id IS NULL THEN
        RETURN;
    END IF;

    SELECT source_method
    INTO job_source_method
    FROM job_schema.jobs
    WHERE id = target_job_id;

    IF job_source_method IS NULL THEN
        RETURN;
    END IF;

    SELECT count(*) INTO link_count
    FROM job_schema.job_link_ingestions
    WHERE job_id = target_job_id;

    SELECT count(*) INTO text_count
    FROM job_schema.job_text_ingestions
    WHERE job_id = target_job_id;

    IF job_source_method = 'link' AND (link_count <> 1 OR text_count <> 0) THEN
        RAISE EXCEPTION 'job % must have exactly one link provenance row and no text provenance rows', target_job_id;
    END IF;

    IF job_source_method = 'text' AND (text_count <> 1 OR link_count <> 0) THEN
        RAISE EXCEPTION 'job % must have exactly one text provenance row and no link provenance rows', target_job_id;
    END IF;

    RETURN;
END
$$;

CREATE OR REPLACE FUNCTION job_schema.enforce_job_source_provenance()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    new_job_id uuid;
    old_job_id uuid;
BEGIN
    new_job_id := COALESCE(
        (to_jsonb(NEW) ->> 'job_id')::uuid,
        (to_jsonb(NEW) ->> 'id')::uuid
    );
    old_job_id := COALESCE(
        (to_jsonb(OLD) ->> 'job_id')::uuid,
        (to_jsonb(OLD) ->> 'id')::uuid
    );

    PERFORM job_schema.assert_job_source_provenance(new_job_id);
    IF old_job_id IS DISTINCT FROM new_job_id THEN
        PERFORM job_schema.assert_job_source_provenance(old_job_id);
    END IF;
    RETURN NULL;
END
$$;

DROP TRIGGER IF EXISTS jobs_source_provenance_enforcement ON job_schema.jobs;
CREATE CONSTRAINT TRIGGER jobs_source_provenance_enforcement
AFTER INSERT OR UPDATE OF source_method ON job_schema.jobs
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION job_schema.enforce_job_source_provenance();

DROP TRIGGER IF EXISTS job_link_ingestions_source_provenance_enforcement ON job_schema.job_link_ingestions;
CREATE CONSTRAINT TRIGGER job_link_ingestions_source_provenance_enforcement
AFTER INSERT OR UPDATE OR DELETE ON job_schema.job_link_ingestions
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION job_schema.enforce_job_source_provenance();

DROP TRIGGER IF EXISTS job_text_ingestions_source_provenance_enforcement ON job_schema.job_text_ingestions;
CREATE CONSTRAINT TRIGGER job_text_ingestions_source_provenance_enforcement
AFTER INSERT OR UPDATE OR DELETE ON job_schema.job_text_ingestions
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION job_schema.enforce_job_source_provenance();
