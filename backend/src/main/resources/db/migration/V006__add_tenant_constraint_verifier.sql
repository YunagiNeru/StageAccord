DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'schema_verifier') THEN
        CREATE ROLE schema_verifier NOLOGIN NOINHERIT;
    END IF;
END
$$;

CREATE FUNCTION infra.verify_tenant_constraints()
RETURNS TABLE (schema_name text, table_name text, violation text)
LANGUAGE sql
SECURITY DEFINER
SET search_path = pg_catalog, infra
AS $$
    WITH tenant_tables AS (
        SELECT c.oid AS table_oid, n.nspname, c.relname, workspace_column.attnum AS workspace_attnum
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_attribute workspace_column
          ON workspace_column.attrelid = c.oid
         AND workspace_column.attname = 'workspace_id'
         AND NOT workspace_column.attisdropped
        WHERE c.relkind IN ('r', 'p')
          AND n.nspname IN (
              'iam', 'workspace', 'catalog', 'intake', 'agreement', 'project',
              'collab', 'file_store', 'privacy', 'schedule', 'billing'
          )
    ), missing_workspace_pk AS (
        SELECT table_oid, nspname, relname, 'PRIMARY_KEY_MISSING_WORKSPACE'::text AS violation
        FROM tenant_tables tenant
        WHERE NOT EXISTS (
            SELECT 1 FROM pg_index index_definition
            WHERE index_definition.indrelid = tenant.table_oid
              AND index_definition.indisprimary
              AND tenant.workspace_attnum = ANY(index_definition.indkey::smallint[])
        )
    ), missing_workspace_fk AS (
        SELECT table_oid, nspname, relname, 'FOREIGN_KEY_MISSING_WORKSPACE'::text AS violation
        FROM tenant_tables tenant
        WHERE NOT EXISTS (
            SELECT 1 FROM pg_constraint relation
            WHERE relation.conrelid = tenant.table_oid
              AND relation.contype = 'f'
              AND tenant.workspace_attnum = ANY(relation.conkey)
        )
    ), unsafe_tenant_fk AS (
        SELECT DISTINCT child.table_oid, child.nspname, child.relname,
               'TENANT_FOREIGN_KEY_NOT_COMPOSITE'::text AS violation
        FROM tenant_tables child
        JOIN pg_constraint relation ON relation.conrelid = child.table_oid AND relation.contype = 'f'
        JOIN tenant_tables parent ON parent.table_oid = relation.confrelid
        WHERE NOT (child.workspace_attnum = ANY(relation.conkey)
               AND parent.workspace_attnum = ANY(relation.confkey))
    )
    SELECT nspname::text, relname::text, violation FROM missing_workspace_pk
    UNION ALL
    SELECT nspname::text, relname::text, violation FROM missing_workspace_fk
    UNION ALL
    SELECT nspname::text, relname::text, violation FROM unsafe_tenant_fk
    ORDER BY 1, 2, 3
$$;

REVOKE ALL ON FUNCTION infra.verify_tenant_constraints() FROM PUBLIC;
GRANT USAGE ON SCHEMA infra TO schema_verifier;
GRANT EXECUTE ON FUNCTION infra.verify_tenant_constraints() TO schema_verifier;
