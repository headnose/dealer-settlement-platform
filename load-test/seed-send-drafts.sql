-- Seeds @n DRAFT wire submissions (4 recipients each) for one send-path load-test run (load-test/wire-send.js).
-- The send path reads only MySQL, then writes to S3 and Kafka: it never calls loan-service or settlement-service,
-- so the drafts need no real loan. Each draft has its own loan id (LT-<run>-<i>), because the duplicate guard
-- refuses a second send for the same loan.
--
-- Usage (run id + count first, then this file), from the repository root:
--   (echo "SET @run='S1'; SET @n=600;"; cat load-test/seed-send-drafts.sql) | docker compose exec -T mysql mysql -udsp -pdsp wire_db
-- Ids: SUB-LT-<run>-1 .. SUB-LT-<run>-<n>, the ids wire-send.js sends. A run id can be seeded only once
-- (primary key); use a new run id for every run.

SET SESSION cte_max_recursion_depth = 100000;

INSERT INTO wire_submission (submission_id, loan_id, wire_type, status, created_time, content)
WITH RECURSIVE seq (i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM seq WHERE i < @n)
SELECT CONCAT('SUB-LT-', @run, '-', i),
       CONCAT('LT-', @run, '-', i),
       'SETTLEMENT',
       'DRAFT',
       NOW(),
       CONCAT('<?xml version="1.0" encoding="UTF-8"?><settlementWire><loanId>LT-', @run, '-', i,
              '</loanId><netSettlement>10000.00</netSettlement></settlementWire>')
FROM seq;

INSERT INTO wire_recipient (recipient_id, submission_id, recipient_type, name, email, delivery_status, last_updated)
WITH RECURSIVE seq (i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM seq WHERE i < @n)
SELECT CONCAT('REC-LT-', @run, '-', seq.i, '-', r.k),
       CONCAT('SUB-LT-', @run, '-', seq.i),
       r.recipient_type,
       r.name,
       r.email,
       'PENDING',
       NULL
FROM seq
CROSS JOIN (SELECT 1 AS k, 'DEALER' AS recipient_type, 'Load Test Dealer' AS name, 'dealer@loadtest.example.com' AS email
            UNION ALL SELECT 2, 'DEALER_BANK_CONTACT', 'Load Test Bank', 'bank@loadtest.example.com'
            UNION ALL SELECT 3, 'AUDIT_DISTRIBUTION', 'Compliance Audit Team', 'compliance-audit@example.com'
            UNION ALL SELECT 4, 'INTERNAL_OPS_DISTRIBUTION', 'Treasury Operations', 'treasury-ops@example.com') AS r;

SELECT @run AS run, COUNT(*) AS drafts_seeded FROM wire_submission
WHERE submission_id LIKE CONCAT('SUB-LT-', @run, '-%') AND status = 'DRAFT';
