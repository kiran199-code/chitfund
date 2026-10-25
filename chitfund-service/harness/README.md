# Sandbox harness (not part of the Maven build)

Runs the real services against a real PostgreSQL without Spring: `PgJdbcTemplate` stands in for `JdbcTemplate` and sends each statement to Postgres through `pgbridge.py` (psycopg2), and transactions are opened and committed by hand. It exists because the sandbox cannot reach Maven Central. On a machine with Maven and Docker, `ChitApplicationIT` and Testcontainers are the proper route.

`run.sh` has the sandbox's paths hard-wired (stub classes in /home/claude/out, Postgres at /tmp/pg:5544); adjust them before using it elsewhere. Scenarios: `Phase6Harness` (wiring, smoke) and `Scenarios` (transfer, recovery, prize).
