-- Add CHECK constraint on drawers.actionability to enforce parity with the Python schema.
-- Python (migrations/0001_initial.sql) has enforced this since day one:
--   actionability TEXT CHECK (actionability IN ('actionable','reference','someday','archive'))
-- The Java baseline (V0002) dropped the CHECK, allowing any text to persist silently.

DO $$
BEGIN
    -- Clean up any legacy rows that would violate the constraint before adding it.
    UPDATE drawers
       SET actionability = NULL
     WHERE actionability IS NOT NULL
       AND actionability NOT IN ('actionable', 'reference', 'someday', 'archive');

    -- Add the constraint only if it does not already exist (idempotent re-run safe).
    IF NOT EXISTS (
        SELECT 1
          FROM information_schema.table_constraints
         WHERE table_schema = current_schema()
           AND table_name = 'drawers'
           AND constraint_name = 'drawers_actionability_check'
    ) THEN
        ALTER TABLE drawers
            ADD CONSTRAINT drawers_actionability_check
            CHECK (actionability IS NULL
                   OR actionability IN ('actionable', 'reference', 'someday', 'archive'));
    END IF;
END $$;
