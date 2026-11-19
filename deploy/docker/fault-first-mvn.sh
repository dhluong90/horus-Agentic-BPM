#!/bin/sh
# Temporary fault injection for #295: the first Maven run fails as Maven Central's false 404 did on
# 20 Nov 2026; later runs are the real Maven. Removed in the next commit.
if [ ! -f /tmp/fault/done ]; then
  touch /tmp/fault/done
  echo "[ERROR] [ERROR] Some problems were encountered while processing the POMs:"
  echo "[ERROR] Non-resolvable import POM: The following artifacts could not be resolved: org.springframework.boot:spring-boot-dependencies:pom:4.1.2 (absent): Could not find artifact org.springframework.boot:spring-boot-dependencies:pom:4.1.2 in central (https://repo.maven.apache.org/maven2) @ line 117, column 19 (injected fault)"
  exit 1
fi
exec "$REAL_MVN" "$@"
