-- A later host migration, used to show the host's history moves on without touching the engine's.
ALTER TABLE loan_application ADD COLUMN status TEXT NOT NULL DEFAULT 'SUBMITTED';
