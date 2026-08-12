ALTER TABLE inventory_operation_journal
    DROP CHECK chk_journal_state,
    ADD CONSTRAINT chk_journal_state CHECK (
        state IN (
            'RECEIVED', 'REDIS_APPLYING', 'REJECTED', 'REDIS_APPLIED', 'COMMITTED', 'COMPENSATED',
            'COMPENSATION_PENDING', 'MIRROR_PENDING', 'REPAIR_REQUIRED'
        )
    );
