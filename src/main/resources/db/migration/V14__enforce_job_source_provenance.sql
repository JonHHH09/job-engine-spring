CREATE OR REPLACE FUNCTION job_schema.enforce_job_source_provenance()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    target_job_id uuid;
    job_source_method text;
    link_count integer;
    text_count integer;
BEGIN
    target_job_id := COALESCE(
        (to_jsonb(NEW) ->> 'job_id')::uuid,
        (to_jsonb(OLD) ->> 'job_id')::uuid,
        (to_jsonb(NEW) ->> 'id')::uuid,
        (to_jsonb(OLD) ->> 'id')::uuid
    );
    IF target_job_id IS NULL THEN
        RETURN NULL;
    END IF;

    SELECT source_method
    INTO job_source_method
    FROM job_schema.jobs
    WHERE id = target_job_id;

    IF job_source_method IS NULL THEN
        RETURN NULL;
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
