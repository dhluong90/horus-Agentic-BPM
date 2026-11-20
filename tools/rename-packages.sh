#!/usr/bin/env bash
# Renames the Maven groupId and the Java package root to io.github.dhluong90.bpm (ADR-0022, issue #21).
#
# Usage (from anywhere inside the repository):
#   tools/rename-packages.sh [apply]           move directories, rewrite references, then verify
#   tools/rename-packages.sh verify [<base>]   check a tree; <base> is the commit before the rename,
#                                               used to prove the migration scripts are unchanged
#   tools/rename-packages.sh flyway-check <old-ref>
#                                               migrate a fresh PostgreSQL with the rule engine built
#                                               from <old-ref>, then start the engine built from the
#                                               working tree on the same database (needs Docker, Maven)
#
# apply is idempotent: a second run changes nothing and only verifies. It refuses to start on a tree
# with uncommitted changes to tracked files. It moves files with `git mv` and rewrites text with perl
# (sed -i differs between BSD and GNU sed). Nothing is committed; review with `git status`.
#
# A branch that was open during the rename is updated by re-running this script after the rebase:
#   git rebase <renamed-main>   # resolve conflicts by taking your side, then:
#   tools/rename-packages.sh && git commit -am "chore: apply package rename"
#
# Environment: MAVEN_REPO_LOCAL (optional, passed as -Dmaven.repo.local to flyway-check builds),
#              POSTGRES_IMAGE (optional, default postgres:16-alpine).
set -euo pipefail

# The old name is kept in segments so that the leftover search does not find this script.
join() { local IFS="$1"; shift; echo "$*"; }
OLD_SEGS=(com horus bpm)
NEW_SEGS=(io github dhluong90 bpm)
OLD_DOT=$(join . "${OLD_SEGS[@]}")
NEW_DOT=$(join . "${NEW_SEGS[@]}")
OLD_PATH=$(join / "${OLD_SEGS[@]}")
NEW_PATH=$(join / "${NEW_SEGS[@]}")
# Any spelling of the old root: dotted, slashed, or regex-escaped as in the licence plugin.
LEFTOVER_PATTERN="${OLD_SEGS[0]}[./\\\\]+${OLD_SEGS[1]}[./\\\\]+${OLD_SEGS[2]}"

# Decision records that tell the history of the rename keep the old name (issue #21 acceptance).
HISTORY_RECORDS=(
  "docs/product/decisions.md"
  "docs/adr/0016-java-api-conventions.md"
  "docs/adr/0022-maven-coordinates-and-package-root.md"
  "CHANGELOG.md"
)

ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"

fail() { echo "rename-packages: FAIL: $*" >&2; exit 1; }
info() { echo "rename-packages: $*"; }

HISTORY_EXCLUDES=()
for record in "${HISTORY_RECORDS[@]}"; do HISTORY_EXCLUDES+=(":(exclude)$record"); done

# ---------------------------------------------------------------------------------------------------
# apply
# ---------------------------------------------------------------------------------------------------

map_path() {
  OLD_PATH="$OLD_PATH" NEW_PATH="$NEW_PATH" perl -e '
    ($p, $o, $n) = ($ARGV[0], @ENV{qw(OLD_PATH NEW_PATH)});
    $p =~ s{/\Q$o\E/processes/}{/$n/process/}; $p =~ s{/\Q$o\E/}{/$n/}; print $p;
  ' "$1"
}

move_paths() {
  local moved=0 src dst
  # Every tracked file under the old root directory, in source and resource trees alike.
  while IFS= read -r src; do
    dst=$(map_path "$src")
    [ "$dst" != "$src" ] || fail "cannot map path $src"
    if [ -e "$dst" ]; then
      fail "collision: $src would overwrite $dst; rename one of them by hand first"
    fi
    mkdir -p "$(dirname "$dst")"
    git mv "$src" "$dst"
    rmdir -p "$(dirname "$src")" 2>/dev/null || true
    moved=$((moved + 1))
  done < <(git ls-files | grep -F "/${OLD_PATH}/" || true)
  info "moved $moved files"
}

rewrite_references() {
  local list count
  # Text files that mention the old root, except the history records. Migration scripts are never
  # rewritten: Flyway checksums their content, so a changed byte would fail validation on every
  # database that has already applied them. verify fails if one still names the old root.
  # NUL-separated through a file: command substitution would drop the NUL bytes.
  list=$(mktemp)
  git grep -I -l -z -E "$LEFTOVER_PATTERN" -- . ${HISTORY_EXCLUDES[@]+"${HISTORY_EXCLUDES[@]}"} ':(exclude)*.sql' > "$list" || true
  count=$(tr -cd "\\000" < "$list" | wc -c | tr -d " ")
  if [ "$count" = 0 ]; then
    rm -f "$list"
    info "rewrote 0 files"
    return
  fi
  OLD_DOT="$OLD_DOT" NEW_DOT="$NEW_DOT" OLD_PATH="$OLD_PATH" NEW_PATH="$NEW_PATH" \
    xargs -0 perl -pi -e '
      BEGIN {
        ($o, $n, $op, $np) = @ENV{qw(OLD_DOT NEW_DOT OLD_PATH NEW_PATH)};
        ($oe = $o) =~ s/\./\\./g; ($ne = $n) =~ s/\./\\./g;
      }
      # Exact phrases whose meaning a plain replacement would break.
      s/\QMaven `groupId`: `$o` or `$n`\E/Maven `groupId` and Java package root/g;
      s/\Q(set once in the parent, rule-engine DESIGN open question 7.2)\E/(set once in the parent, ADR-0022)/g;
      # ADR-0022 names the process engine root "process"; one string still says "processes".
      s/(?<![\w.])\Q$o.processes.\E/$n.process./g;
      s/\Q$oe\E/$ne/g;
      s/(?<![\w.])\Q$o\E(?!\w)/$n/g;
      s/\Q$op\E/$np/g;
    ' < "$list"
  rm -f "$list"
  info "rewrote $count files"
}

cmd_apply() {
  git diff --quiet && git diff --cached --quiet || fail "uncommitted changes to tracked files; commit or stash first"
  local base
  base=$(git rev-parse HEAD)
  move_paths
  rewrite_references
  cmd_verify "$base"
}

# ---------------------------------------------------------------------------------------------------
# verify
# ---------------------------------------------------------------------------------------------------

check_leftovers() {
  local hits paths
  hits=$(git grep --untracked -n -I -E "$LEFTOVER_PATTERN" -- . ${HISTORY_EXCLUDES[@]+"${HISTORY_EXCLUDES[@]}"} || true)
  if [ -n "$hits" ]; then
    printf '%s\n' "$hits" >&2
    fail "the old package root is still named in $(printf '%s\n' "$hits" | cut -d: -f1 | sort -u | wc -l | tr -d ' ') files (above)"
  fi
  paths=$(git ls-files --cached --others --exclude-standard | grep -E "(^|/)${OLD_PATH}/" || true)
  [ -z "$paths" ] || { printf '%s\n' "$paths" >&2; fail "files still live under ${OLD_PATH}/"; }
  info "ok: no reference to the old root outside the history records ($(git grep -c -E "$LEFTOVER_PATTERN" -- "${HISTORY_RECORDS[@]}" 2>/dev/null | awk -F: '{s+=$2} END {print s+0}') lines kept there)"
}

check_package_declarations() {
  local bad
  # The package declaration of every Java file matches its directory under src/*/java/.
  bad=$(git ls-files --cached --others --exclude-standard -- '*.java' | grep -E '/src/[^/]+/java/' | perl -ne '
    chomp; my $f = $_; (my $dir = $f) =~ s{^.*?/src/[^/]+/java/}{}; $dir =~ s{/[^/]+$}{}; $dir =~ s{/}{.}g;
    open(my $fh, "<", $f) or next; my $pkg = "";
    while (<$fh>) { if (/^\s*package\s+([\w.]+)\s*;/) { $pkg = $1; last } }
    print "$f declares \"$pkg\"\n" if $pkg ne $dir;
  ')
  [ -z "$bad" ] || { printf '%s\n' "$bad" >&2; fail "package declarations that do not match their directory"; }
  info "ok: every Java package declaration matches its directory"
}

check_autoconfiguration_imports() {
  local file entry missing="" sources
  sources=$(git ls-files --cached --others --exclude-standard -- '*.java')
  for file in $(git ls-files -- '*META-INF/spring/*.imports'); do
    while IFS= read -r entry; do
      entry="${entry%%#*}"; entry="$(echo "$entry" | tr -d '[:space:]')"
      [ -n "$entry" ] || continue
      case "$entry" in "$NEW_DOT".*) ;; *) continue ;; esac
      printf '%s\n' "$sources" | grep -q -F "/java/${entry//.//}.java" || missing="$missing\n$file: $entry"
    done < "$file"
  done
  [ -z "$missing" ] || { printf "$missing\n" >&2; fail "auto-configuration entries with no source file"; }
  # afterName/beforeName may name a module that is not in this tree yet; report, do not fail.
  git grep -h -o -E "\"${NEW_DOT//./\\.}\.[A-Za-z0-9_.]+AutoConfiguration\"" -- '*.java' | tr -d '"' | sort -u |
    while IFS= read -r entry; do
      printf '%s\n' "$sources" | grep -q -F "/java/${entry//.//}.java" || info "note: $entry is named but not in this tree"
    done
  # ADR-0022: the process engine root is "process". A "processes" package names a class that never
  # exists, and Spring Boot silently ignores an ordering hint that names a missing class.
  local wrong
  wrong=$(git grep -n -F "${NEW_DOT}.processes." -- '*.java' '*.imports' || true)
  [ -z "$wrong" ] || { printf '%s\n' "$wrong" >&2; fail "references to a ${NEW_DOT}.processes package (ADR-0022 says process)"; }
  info "ok: every auto-configuration registration resolves to a class"
}

check_migrations_unchanged() {
  local base="$1" changes
  [ -n "$base" ] || { info "skip: migration check needs the pre-rename commit (verify <base>)"; return; }
  git rev-parse --verify -q "$base^{commit}" >/dev/null || fail "unknown base $base"
  # Against the commit before the rename, a migration script may only move, byte for byte (R100).
  # Flyway keys its history on the version and on the script name relative to the location, and
  # compares a CRC32 of the content; the classpath location itself is not recorded.
  changes=$(git diff --find-renames=100% --name-status "$base" -- '*.sql' | grep -v -E '^R100' || true)
  if [ -n "$changes" ]; then
    printf '%s\n' "$changes" >&2
    fail "migration scripts changed content or disappeared against $base (above); only exact moves are allowed"
  fi
  info "ok: $(git diff --find-renames=100% --name-status "$base" -- '*.sql' | grep -c '^R100' || true) migration scripts moved byte for byte, none edited"
}

cmd_verify() {
  check_leftovers
  check_package_declarations
  check_autoconfiguration_imports
  check_migrations_unchanged "${1:-}"
  info "verify passed"
}

# ---------------------------------------------------------------------------------------------------
# flyway-check
# ---------------------------------------------------------------------------------------------------

build_classpath() {
  local tree="$1" out="$2"
  local repo_args=()
  [ -z "${MAVEN_REPO_LOCAL:-}" ] || repo_args=("-Dmaven.repo.local=$MAVEN_REPO_LOCAL")
  mvn -B -ntp -q -f "$tree/pom.xml" ${repo_args[@]+"${repo_args[@]}"} -pl rule-engine/rule-engine-postgres -am \
    -DskipTests -Dcockpit.bundle.skip=true \
    package dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile="$out"
  printf ':%s' "$tree/rule-engine/rule-engine-postgres/target/classes" >> "$out"
}

migrator_class() {
  # The engine's migrator, located by file name so the check works on either side of the rename.
  local tree="$1" f
  f=$(cd "$tree" && git ls-files -- '*/rule-engine-postgres/src/main/java/*/RuleEngineSchemaMigrator.java')
  [ -n "$f" ] || fail "RuleEngineSchemaMigrator not found in $tree"
  f="${f#*/src/main/java/}"; f="${f%.java}"; echo "${f//\//.}"
}

cmd_flyway_check() {
  local old_ref="${1:-}"
  [ -n "$old_ref" ] || fail "usage: flyway-check <old-ref>"
  local port url
  # Globals, so the EXIT trap still sees them after this function returns.
  FC_WORK=$(mktemp -d "${TMPDIR:-/tmp}/rename-flyway.XXXXXX")
  FC_OLD="$FC_WORK/old"
  FC_CONTAINER=""
  local work="$FC_WORK" old="$FC_OLD"
  cleanup() {
    [ -z "$FC_CONTAINER" ] || docker rm -f "$FC_CONTAINER" >/dev/null 2>&1 || true
    git -C "$ROOT" worktree remove --force "$FC_OLD" >/dev/null 2>&1 || true
    rm -rf "$FC_WORK"
  }
  trap cleanup EXIT

  git worktree add --detach -q "$old" "$old_ref"
  info "building the rule engine at $old_ref and in the working tree"
  build_classpath "$old" "$work/cp-old.txt"
  build_classpath "$ROOT" "$work/cp-new.txt"

  FC_CONTAINER=$(docker run -d -e POSTGRES_PASSWORD=rename -e POSTGRES_DB=host -p 127.0.0.1::5432 \
    "${POSTGRES_IMAGE:-postgres:16-alpine}")
  port=$(docker port "$FC_CONTAINER" 5432/tcp | head -1 | sed 's/.*://')
  for _ in $(seq 1 60); do docker exec "$FC_CONTAINER" pg_isready -U postgres -d host >/dev/null 2>&1 && break; sleep 1; done
  url="jdbc:postgresql://127.0.0.1:$port/host"

  cat > "$work/FlywayRenameCheck.java" <<'JAVA'
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;

/** Runs the engine's own migrator by class name and prints the engine's Flyway history. */
public class FlywayRenameCheck {
    public static void main(String[] a) throws Exception {
        String migrator = a[0], url = a[1], phase = a[2];
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(url); ds.setUser("postgres"); ds.setPassword("rename");
        Object m = Class.forName(migrator).getConstructor(DataSource.class).newInstance(ds);
        if (phase.equals("tamper")) {
            // Negative control: a changed checksum must stop the engine, so a clean start means something.
            exec(ds, "UPDATE rle_schema_history SET checksum = checksum + 1 WHERE version = '1'");
            try {
                m.getClass().getMethod("migrate").invoke(m);
                exec(ds, "UPDATE rle_schema_history SET checksum = checksum - 1 WHERE version = '1'");
                throw new IllegalStateException("a tampered checksum was not detected");
            } catch (java.lang.reflect.InvocationTargetException e) {
                exec(ds, "UPDATE rle_schema_history SET checksum = checksum - 1 WHERE version = '1'");
                System.out.println("tamper: migrate refused: " + e.getCause().getClass().getSimpleName()
                        + ": " + e.getCause().getMessage().lines().findFirst().orElse(""));
            }
            return;
        }
        Object result = m.getClass().getMethod("migrate").invoke(m);
        Object status = m.getClass().getMethod("status").invoke(m);
        System.out.println(phase + ": " + result);
        System.out.println(phase + ": " + status);
        System.out.println(phase + ": executed=" + result.getClass().getMethod("migrationsExecuted").invoke(result));
        try (Connection c = ds.getConnection(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT installed_rank, version, description, type, script, checksum, success"
                     + " FROM rle_schema_history ORDER BY installed_rank")) {
            while (r.next()) {
                System.out.println("history: " + r.getInt(1) + " | " + r.getString(2) + " | " + r.getString(3) + " | "
                        + r.getString(4) + " | " + r.getString(5) + " | " + r.getObject(6) + " | " + r.getBoolean(7));
            }
        }
    }

    static void exec(DataSource ds, String sql) throws SQLException {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) { s.executeUpdate(sql); }
    }
}
JAVA

  local old_class new_class out_old out_new
  old_class=$(migrator_class "$old")
  new_class=$(migrator_class "$ROOT")
  info "old migrator: $old_class"
  info "new migrator: $new_class"
  out_old=$(java -cp "$(cat "$work/cp-old.txt")" "$work/FlywayRenameCheck.java" "$old_class" "$url" old)
  printf '%s\n' "$out_old"
  out_new=$(java -cp "$(cat "$work/cp-new.txt")" "$work/FlywayRenameCheck.java" "$new_class" "$url" new)
  printf '%s\n' "$out_new"
  java -cp "$(cat "$work/cp-new.txt")" "$work/FlywayRenameCheck.java" "$new_class" "$url" tamper

  printf '%s\n' "$out_new" | grep -q '^new: executed=0$' || fail "the renamed engine applied migrations again"
  [ "$(printf '%s\n' "$out_old" | grep '^history:')" = "$(printf '%s\n' "$out_new" | grep '^history:')" ] ||
    fail "the engine history changed between the old and the renamed engine"
  printf '%s\n' "$out_old" | grep -q '^old: executed=0$' && fail "the old engine applied nothing; the check proved nothing"
  info "flyway-check passed: the renamed engine validated the history written by $old_ref and applied nothing"
}

case "${1:-apply}" in
  apply) cmd_apply ;;
  verify) cmd_verify "${2:-}" ;;
  flyway-check) cmd_flyway_check "${2:-}" ;;
  -h|--help) sed -n '2,22p' "$0" ;;
  *) fail "unknown command $1 (apply | verify [<base>] | flyway-check <old-ref>)" ;;
esac
