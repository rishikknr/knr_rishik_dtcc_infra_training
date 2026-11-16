-- Additive evidence only. Existing credentials, settings and tenant policies are untouched.
ALTER TABLE source_connections ADD COLUMN last_successful_test_at timestamptz;
ALTER TABLE source_connections ADD COLUMN last_test_diagnostic_id uuid;
-- Historical last_tested_at records attempts, so do not infer a successful test from it.
