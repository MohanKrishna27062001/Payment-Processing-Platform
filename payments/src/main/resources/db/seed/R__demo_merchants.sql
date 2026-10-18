-- Demo merchants for local development only (excluded from production via spring.flyway.locations).
-- Two merchants make it possible to demonstrate tenant isolation.
--
--   acme@example.com   / demo-password
--   globex@example.com / demo-password
--
-- Passwords are bcrypt-hashed by pgcrypto, which Spring's BCryptPasswordEncoder can verify.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

INSERT INTO merchants (id, name, email, password_hash)
VALUES ('11111111-1111-1111-1111-111111111111', 'Acme Shoes', 'acme@example.com',
        crypt('demo-password', gen_salt('bf', 10))),
       ('22222222-2222-2222-2222-222222222222', 'Globex Electronics', 'globex@example.com',
        crypt('demo-password', gen_salt('bf', 10)))
ON CONFLICT (email) DO NOTHING;
