-- The gateway authenticates a batch by its SDK key before any tenant is
-- known, so a second permissive policy lets a transaction that presents the
-- exact key read exactly that app row. current_setting(..., true) returns
-- NULL when the setting is absent, which matches no row.
--
-- allowed_properties is the default-deny event-property allowlist, and with
-- an empty array the gateway drops every property.

ALTER TABLE app ADD COLUMN allowed_properties text[] NOT NULL DEFAULT '{}';

CREATE POLICY app_sdk_key_lookup ON app
    FOR SELECT
    USING (sdk_key = current_setting('app.sdk_key', true));
