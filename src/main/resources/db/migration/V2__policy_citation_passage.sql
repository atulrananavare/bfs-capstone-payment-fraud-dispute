-- Phase 4: persist exact retrieved passage and display metadata on
-- policy citations so a reviewer can audit the grounded text verbatim.
ALTER TABLE policy_citation ADD COLUMN title VARCHAR(160);
ALTER TABLE policy_citation ADD COLUMN effective_date DATE;
ALTER TABLE policy_citation ADD COLUMN passage VARCHAR(4000);
