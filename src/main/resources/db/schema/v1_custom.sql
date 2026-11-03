CREATE OR REPLACE FUNCTION prevent_last_company_admin_user_delete()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM company.id
    FROM companies AS company
    INNER JOIN company_memberships AS membership ON membership.company_id = company.id
    WHERE membership.user_id = OLD.id
      AND membership.role = 'ADMIN'
    ORDER BY company.id
    FOR UPDATE OF company;

    IF EXISTS (
        SELECT 1
        FROM company_memberships AS current_admin
        WHERE current_admin.user_id = OLD.id
          AND current_admin.role = 'ADMIN'
          AND NOT EXISTS (
              SELECT 1
              FROM company_memberships AS replacement_admin
              WHERE replacement_admin.company_id = current_admin.company_id
                AND replacement_admin.role = 'ADMIN'
                AND replacement_admin.user_id <> OLD.id
          )
    ) THEN
        RAISE EXCEPTION
            USING ERRCODE = '23514',
                  CONSTRAINT = 'users_last_company_admin_delete',
                  MESSAGE = 'Cannot delete a user who is the last company admin; promote another member first';
    END IF;

    RETURN OLD;
END;
$$;
CREATE TRIGGER users_last_company_admin_delete
BEFORE DELETE ON users
FOR EACH ROW
EXECUTE FUNCTION prevent_last_company_admin_user_delete();

CREATE OR REPLACE FUNCTION cleanup_vehicle_locations_for_user_delete()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    DELETE FROM vehicle_locations
    WHERE vendor_user_id = OLD.id
      AND assignment_id IS NOT NULL;

    UPDATE vehicle_locations
    SET vendor_user_id = NULL
    WHERE vendor_user_id = OLD.id
      AND assignment_id IS NULL;

    RETURN OLD;
END;
$$;
CREATE TRIGGER users_vehicle_locations_cleanup
BEFORE DELETE ON users
FOR EACH ROW
EXECUTE FUNCTION cleanup_vehicle_locations_for_user_delete();
