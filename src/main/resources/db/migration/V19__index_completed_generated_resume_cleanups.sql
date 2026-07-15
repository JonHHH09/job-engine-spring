CREATE INDEX idx_generated_resume_file_cleanups_completed_retention
    ON document.generated_resume_file_cleanups (completed_at, id)
    WHERE status = 'COMPLETED';
