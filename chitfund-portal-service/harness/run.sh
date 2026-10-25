#!/bin/bash
# Compile + run the portal harness against a real PostgreSQL. Needs /home/claude/build_portal.sh to have run (stubs + compiled main).
set -e
LIBS=/opt/gradle-8.14.3/lib
CP=$LIBS/jackson-annotations-2.16.1.jar:$LIBS/jackson-core-2.16.1.jar:$LIBS/jackson-databind-2.16.1.jar:$LIBS/jackson-datatype-jsr310-2.16.1.jar:$LIBS/slf4j-api-1.7.36.jar
export JAVA_TOOL_OPTIONS=
export PG_SEARCH_PATH=portal
export PGHOST=/tmp/pg PGPORT=5544 PGUSER=postgres
DBN=${DB:-portal1}
R=/home/claude/cf/chitfund-portal-service
psql -q -d postgres -c "DROP DATABASE IF EXISTS $DBN" -c "CREATE DATABASE $DBN" >/dev/null
psql -q -d $DBN -c "CREATE SCHEMA portal" >/dev/null
for f in $R/src/main/resources/db/migration/V*.sql; do PGOPTIONS="-c search_path=portal" psql -q -v ON_ERROR_STOP=1 -d $DBN -f $f >/dev/null || { echo "migration failed: $f"; exit 1; }; done
O=/home/claude/out_portal/harness; rm -rf $O; mkdir -p $O
javac -nowarn --release 17 -parameters -cp /home/claude/out_portal/stubs:/home/claude/out_portal/main:$CP -d $O $R/harness/src/harness/*.java
java -Ddb=$DBN -cp $O:/home/claude/out_portal/stubs:/home/claude/out_portal/main:$CP harness.PortalHarness "$@"
