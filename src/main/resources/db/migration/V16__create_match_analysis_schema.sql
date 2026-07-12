CREATE SCHEMA IF NOT EXISTS match;

CREATE TABLE match.reports (
    id uuid PRIMARY KEY,
    profile_id uuid NOT NULL REFERENCES profile.profiles(id) ON DELETE CASCADE,
    job_id uuid NOT NULL REFERENCES job_schema.jobs(id) ON DELETE CASCADE,
    profile_revision timestamptz NOT NULL,
    job_revision timestamptz NOT NULL,
    algorithm_version text NOT NULL CHECK (btrim(algorithm_version) <> ''),
    overall_score integer NOT NULL CHECK (overall_score BETWEEN 0 AND 100),
    confidence integer NOT NULL CHECK (confidence BETWEEN 0 AND 100),
    outcome text NOT NULL CHECK (outcome IN ('STRONG_MATCH','PARTIAL_MATCH','WEAK_MATCH','INSUFFICIENT_EVIDENCE')),
    blocker_mismatch boolean NOT NULL,
    components jsonb NOT NULL CHECK (jsonb_typeof(components) = 'array'),
    evidence jsonb NOT NULL CHECK (jsonb_typeof(evidence) = 'array'),
    created_at timestamptz NOT NULL,
    CONSTRAINT reports_revision_unique UNIQUE (algorithm_version, profile_id, profile_revision, job_id, job_revision)
);

CREATE TABLE match.reviews (
    id uuid PRIMARY KEY,
    fingerprint uuid NOT NULL UNIQUE,
    report_id uuid NOT NULL REFERENCES match.reports(id) ON DELETE CASCADE,
    reviewer text NOT NULL CHECK (btrim(reviewer) <> ''), model text NOT NULL CHECK (btrim(model) <> ''),
    review_version text NOT NULL CHECK (btrim(review_version) <> ''),
    overall_score integer NOT NULL CHECK (overall_score BETWEEN 0 AND 100),
    outcome text NOT NULL CHECK (outcome IN ('STRONG_MATCH','PARTIAL_MATCH','WEAK_MATCH','INSUFFICIENT_EVIDENCE')),
    blocker_mismatch boolean NOT NULL,
    components jsonb NOT NULL CHECK (jsonb_typeof(components) = 'array'),
    evidence jsonb NOT NULL CHECK (jsonb_typeof(evidence) = 'array'),
    summary text NOT NULL CHECK (summary IN ('review_consistent','score_adjustment','outcome_adjustment','evidence_defect_identified')),
    created_at timestamptz NOT NULL,
    CONSTRAINT reviews_id_report_unique UNIQUE (id, report_id)
);

CREATE TABLE match.disagreements (
    id uuid PRIMARY KEY,
    fingerprint uuid NOT NULL UNIQUE,
    report_id uuid NOT NULL REFERENCES match.reports(id) ON DELETE CASCADE,
    review_id uuid NOT NULL REFERENCES match.reviews(id) ON DELETE CASCADE,
    policy_version text NOT NULL CHECK (policy_version = 'divergence-v1'),
    reasons jsonb NOT NULL CHECK (jsonb_typeof(reasons) = 'array'),
    evidence_defect_codes jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (
        jsonb_typeof(evidence_defect_codes) = 'array'
        AND evidence_defect_codes <@ '["structured_evidence_missing","structured_evidence_incorrect","requirement_provenance_missing","outcome_calibration_issue"]'::jsonb
    ),
    status text NOT NULL CHECK (status IN ('OPEN','PENDING_ESCALATION','ACKNOWLEDGED','LINKED')),
    linear_issue_id text CHECK (linear_issue_id IS NULL OR btrim(linear_issue_id) <> ''),
    created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
    CONSTRAINT disagreements_review_policy_unique UNIQUE (review_id, policy_version),
    CONSTRAINT disagreements_review_report_fk FOREIGN KEY (review_id, report_id)
        REFERENCES match.reviews(id, report_id) ON DELETE CASCADE
);

CREATE INDEX reports_profile_job_created_idx ON match.reports(profile_id, job_id, created_at DESC);
CREATE INDEX reviews_report_created_idx ON match.reviews(report_id, created_at DESC);
CREATE INDEX disagreements_report_created_idx ON match.disagreements(report_id, created_at DESC);

CREATE OR REPLACE FUNCTION match.reject_immutable_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'match history is immutable'; END $$;
CREATE TRIGGER reports_immutable BEFORE UPDATE ON match.reports FOR EACH ROW EXECUTE FUNCTION match.reject_immutable_update();
CREATE TRIGGER reviews_immutable BEFORE UPDATE ON match.reviews FOR EACH ROW EXECUTE FUNCTION match.reject_immutable_update();
