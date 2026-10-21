-- Demo merchants for local development only (excluded from production via spring.flyway.locations).
-- Two merchants make it possible to demonstrate tenant isolation.
--
--   argyle@example.com / demo-password   (Argyle Payments)
--   globex@example.com / demo-password   (Globex Electronics)
--
-- Passwords are bcrypt-hashed by pgcrypto, which Spring's BCryptPasswordEncoder can verify.
--
-- This is a REPEATABLE migration (R__): Flyway re-applies it whenever its checksum changes, so editing a
-- merchant's name or email here updates the existing row on the next startup. The conflict target is the
-- fixed id rather than the email, which is what lets an email change through -- ON CONFLICT (email) would
-- leave the old row behind under its old address and insert a second one alongside it.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

INSERT INTO merchants (id, name, email, password_hash)
VALUES ('11111111-1111-1111-1111-111111111111', 'Argyle Payments', 'argyle@example.com',
        crypt('demo-password', gen_salt('bf', 10))),
       ('22222222-2222-2222-2222-222222222222', 'Globex Electronics', 'globex@example.com',
        crypt('demo-password', gen_salt('bf', 10)))
ON CONFLICT (id) DO UPDATE
    SET name          = EXCLUDED.name,
        email         = EXCLUDED.email,
        password_hash = EXCLUDED.password_hash;
