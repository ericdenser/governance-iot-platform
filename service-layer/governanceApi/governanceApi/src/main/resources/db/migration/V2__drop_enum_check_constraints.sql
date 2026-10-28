-- Hibernate generated CHECK constraints listing every enum value. Any new enum
-- constant in the code then fails on insert until the constraint is rebuilt.
-- The application validates enums, so drop them (works whatever their names are).
DO $$
DECLARE
    c record;
BEGIN
    FOR c IN
        SELECT con.conrelid::regclass AS tbl, con.conname
        FROM pg_constraint con
        JOIN pg_namespace ns ON ns.oid = con.connamespace
        WHERE con.contype = 'c'
          AND ns.nspname = 'public'
          AND pg_get_constraintdef(con.oid) LIKE '%= ANY (%ARRAY[%'
    LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', c.tbl, c.conname);
    END LOOP;
END $$;
