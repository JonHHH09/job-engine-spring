DROP INDEX match.reviews_report_created_idx;
DROP INDEX match.disagreements_report_created_idx;

CREATE INDEX reviews_list_created_id_idx
    ON match.reviews (created_at DESC, id);

CREATE INDEX reviews_report_created_id_idx
    ON match.reviews (report_id, created_at DESC, id);

CREATE INDEX disagreements_list_created_id_idx
    ON match.disagreements (created_at DESC, id);

CREATE INDEX disagreements_report_created_id_idx
    ON match.disagreements (report_id, created_at DESC, id);
