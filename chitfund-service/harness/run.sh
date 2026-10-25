#!/bin/bash
# compile + run the harness. usage: run.sh [smoke|transfer|recovery]   (db = chit7 unless -Ddb=...)
set -e
LIBS=/opt/gradle-8.14.3/lib
CP=$LIBS/jackson-annotations-2.16.1.jar:$LIBS/jackson-core-2.16.1.jar:$LIBS/jackson-databind-2.16.1.jar:$LIBS/jackson-datatype-jsr310-2.16.1.jar:$LIBS/slf4j-api-1.7.36.jar
export JAVA_TOOL_OPTIONS=
export PGHOST=/tmp/pg PGPORT=5544 PGUSER=postgres
DBN=${DB:-chit7}
psql -q -d postgres -c "DROP DATABASE IF EXISTS $DBN" -c "CREATE DATABASE $DBN" >/dev/null
for f in /home/claude/cf/chitfund-service/src/main/resources/db/migration/V*.sql; do psql -q -v ON_ERROR_STOP=1 -d $DBN -f $f >/dev/null || { echo "migration failed: $f"; exit 1; }; done
H=/home/claude/harness; O=/home/claude/out/harness; rm -rf $O; mkdir -p $O
javac -nowarn --release 17 -parameters -cp /home/claude/out/stubs:/home/claude/out/main:$CP -d $O $H/src/harness/*.java
java -Ddb=${DB:-chit7} -cp $O:/home/claude/out/stubs:/home/claude/out/main:$CP harness.Phase6Harness "$@"
