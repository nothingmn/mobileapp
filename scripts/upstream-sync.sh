#!/usr/bin/env bash
# Report what an upstream sync would collide with, and optionally do the whole thing on a scratch worktree.
#
# Written for the next person who has to move this fork onto a newer upstream. See UPSTREAMING.md for the reasoning;
# this file is the mechanics. Two jobs, one script, because the report is what makes the merge cheap and a report you
# have to re-derive by hand is one nobody runs.
#
#   scripts/upstream-sync.sh                 # report only: behind/ahead, commits, conflict surface
#   scripts/upstream-sync.sh --rebase        # the above, then replay our commits onto origin/master in a worktree
#   scripts/upstream-sync.sh --verify DIR    # build both flag states + run the suites in worktree DIR
#   scripts/upstream-sync.sh --all           # --rebase then --verify, printing one verdict
#
# Rules it keeps, so that running it twice is safe and running it at all is safe on the operator's checkout:
#   * the report touches no working tree and no index;
#   * it refuses to act on a branch that is checked out in the main worktree (the operator's branch);
#   * the scratch worktree is created from origin/master, never from our branch;
#   * an interrupted cherry-pick is left where it is and reported, never reset or cleaned;
#   * nothing is pushed, nothing is installed, no phone is touched.
#
# Environment: GRADLE_USER_HOME may be set; the Android build needs JAVA_HOME and ANDROID_HOME (see
# ~/.local/opt/gravel-env.sh on the operator's machine).

set -uo pipefail

# The branch whose commits we carry, and the upstream ref we carry them onto. Overridable for a different fork state.
OUR_BRANCH="${OUR_BRANCH:-feature/mqtt-voice}"
UPSTREAM_REF="${UPSTREAM_REF:-origin/master}"

# The five modules whose host suites carry this fork's features, in the order the sync report lists them.
TEST_TASKS=(
    ":voice-mqtt:testAndroidHostTest"
    ":ring:testAndroidHostTest"
    ":notebook:testAndroidHostTest"
    ":pebble:testAndroidHostTest"
    ":composeApp:testAndroidHostTest"
)

# What must be absent from a flag-off build's dex, and present in a flag-on one.
VOICE_PACKAGE="com/anopticlabs/gravel/voicemqtt"

die() { printf 'upstream-sync: %s\n' "$1" >&2; exit 1; }
say() { printf '%s\n' "$*"; }
rule() { printf '%s\n' "-------------------------------------------------------------------------------"; }

usage() {
    sed -n '2,25p' "$0" | sed 's/^# \{0,1\}//'
    exit 0
}

# ---------------------------------------------------------------------------------------------------------------
# Where we are
# ---------------------------------------------------------------------------------------------------------------

# The main working tree: the first entry of `git worktree list` is always the primary checkout, whatever we are
# invoked from. Everything that must not be disturbed is answered relative to it.
main_worktree() {
    git worktree list --porcelain | sed -n '1s/^worktree //p'
}

repo_root() {
    git rev-parse --show-toplevel 2>/dev/null || die "not inside a git repository"
}

# Is [branch] checked out anywhere other than [here]? Prints the path that holds it, or nothing.
branch_holder() {
    local branch="$1" here="$2"
    git worktree list --porcelain |
        awk -v want="refs/heads/$branch" -v here="$here" '
            /^worktree / { path = substr($0, 10) }
            /^branch /   { if (substr($0, 8) == want && path != here) { print path; exit } }
        '
}

# ---------------------------------------------------------------------------------------------------------------
# The report
# ---------------------------------------------------------------------------------------------------------------

# Files where both sides changed since the merge base: the surface a merge may stop on. Read-only.
conflict_surface() {
    local base="$1" ours="$2" theirs="$3" tmp
    tmp="$(mktemp -d)"
    git diff --name-only "$base" "$ours"   | LC_ALL=C sort > "$tmp/ours"
    git diff --name-only "$base" "$theirs" | LC_ALL=C sort > "$tmp/theirs"
    comm -12 "$tmp/ours" "$tmp/theirs"
    rm -rf "$tmp"
}

# Files only our side changed: they replay without a conflict, but upstream may have moved what they compile against.
ours_only() {
    local base="$1" ours="$2" theirs="$3" tmp
    tmp="$(mktemp -d)"
    git diff --name-only "$base" "$ours"   | LC_ALL=C sort > "$tmp/ours"
    git diff --name-only "$base" "$theirs" | LC_ALL=C sort > "$tmp/theirs"
    comm -23 "$tmp/ours" "$tmp/theirs"
    rm -rf "$tmp"
}

# The top-level directory a path belongs to, for the "which modules did each side touch" line.
module_of() {
    case "$1" in
        */*) printf '%s\n' "${1%%/*}" ;;
        *)   printf '%s\n' "(root files)" ;;
    esac
}

report() {
    local main ours theirs base behind ahead
    main="$(main_worktree)"
    ours="$(git rev-parse --verify --quiet "refs/heads/$OUR_BRANCH" || true)"
    [ -n "$ours" ] || die "no local branch $OUR_BRANCH"
    theirs="$(git rev-parse --verify --quiet "$UPSTREAM_REF")" || die "no $UPSTREAM_REF; run git fetch origin"
    base="$(git merge-base "$ours" "$theirs")" || die "no merge base between $OUR_BRANCH and $UPSTREAM_REF"
    behind="$(git rev-list --count "$base..$theirs")"
    ahead="$(git rev-list --count "$base..$ours")"

    say "main worktree: $main"
    say "our branch:    $OUR_BRANCH at $(git rev-parse --short "$ours") (subject: $(git log -1 --format=%s "$ours"))"
    say "upstream:      $UPSTREAM_REF at $(git rev-parse --short "$theirs") (subject: $(git log -1 --format=%s "$theirs"))"
    say "merge base:    $(git rev-parse --short "$base")"
    say ""
    if [ "$behind" -eq 0 ]; then
        say "VERDICT: nothing to sync — $OUR_BRANCH already contains every commit in $UPSTREAM_REF."
    else
        say "VERDICT: $behind upstream commit(s) to take, $ahead of ours to replay."
    fi
    say ""

    say "upstream commits we do not have:"
    git log --oneline --no-decorate "$base..$theirs" | sed 's/^/  /'
    say ""
    say "our commits upstream does not have:"
    git log --oneline --no-decorate "$base..$ours" | sed 's/^/  /'
    say ""

    local surface
    surface="$(conflict_surface "$base" "$ours" "$theirs")"
    rule
    if [ -z "$surface" ]; then
        say "conflict surface: none — a merge of these two lines cannot stop on a file."
    else
        say "conflict surface ($(printf '%s\n' "$surface" | wc -l | tr -d ' ') files changed by both sides):"
        printf '%s\n' "$surface" | sed 's/^/  /'
        say ""
        say "Each of these may stop the merge. Read the two sides' commits before resolving (UPSTREAMING.md §2.4):"
        while IFS= read -r path; do
            say "  $path"
            say "    upstream: $(git log --oneline --no-decorate "$base..$theirs" -- "$path" | head -1 | cut -c1-90)"
            say "    ours:     $(git log --oneline --no-decorate "$base..$ours" -- "$path" | head -1 | cut -c1-90)"
        done <<< "$surface"
    fi
    say ""

    local only count
    only="$(ours_only "$base" "$ours" "$theirs")"
    if [ -n "$only" ]; then
        count="$(printf '%s\n' "$only" | wc -l | tr -d ' ')"
        rule
        say "files only we changed ($count): no conflict, but they must still compile against upstream's new code."
        if [ "$count" -gt 25 ] && [ "${VERBOSE:-0}" != "1" ]; then
            printf '%s\n' "$only" | head -25 | sed 's/^/  /'
            say "  ... and $((count - 25)) more (VERBOSE=1 for the whole list)"
        else
            printf '%s\n' "$only" | sed 's/^/  /'
        fi
        say ""
    fi

    rule
    say "modules upstream touched:"
    git diff --name-only "$base" "$theirs" | while IFS= read -r path; do module_of "$path"; done | LC_ALL=C sort -u | sed 's/^/  /'
    say "modules we touched:"
    git diff --name-only "$base" "$ours" | while IFS= read -r path; do module_of "$path"; done | LC_ALL=C sort -u | sed 's/^/  /'
    return 0
}

# ---------------------------------------------------------------------------------------------------------------
# The rebase, on a scratch worktree
# ---------------------------------------------------------------------------------------------------------------

scratch_dir() {
    local main
    main="$(main_worktree)"
    printf '%s' "${SCRATCH_DIR:-$(dirname "$main")/$(basename "$main")-sync-$(date +%Y-%m-%d)}"
}

rebase() {
    local main ours theirs base target branch holder ahead
    main="$(main_worktree)"
    ours="$(git rev-parse --verify "refs/heads/$OUR_BRANCH")"
    theirs="$(git rev-parse --verify "$UPSTREAM_REF")" || die "no $UPSTREAM_REF; run git fetch origin"
    base="$(git merge-base "$ours" "$theirs")"
    ahead="$(git rev-list --count "$base..$ours")"
    target="$(scratch_dir)"
    branch="${SCRATCH_BRANCH:-upstream-sync-$(date +%Y-%m-%d)}"

    # The safety rule this script exists to keep: the operator's branch is never moved, so a branch checked out in
    # another worktree is refused here rather than discovered as a conflict in the middle of a cherry-pick.
    # The operator's branch is the one his phone runs. Never operate on a branch the main worktree has checked out,
    # whatever SCRATCH_BRANCH says.
    if [ "$(branch_holder "$branch" "$target")" = "$main" ]; then
        die "refusing to touch $branch: it is checked out in the main worktree ($main)"
    fi

    # Refused only when the branch is held somewhere we are not about to reuse: a worktree that is ours to look at is
    # inspected below, and refused there if it is mid-operation.
    holder="$(branch_holder "$branch" "")"
    if [ -n "$holder" ] && [ "$holder" != "$target" ]; then
        die "$branch is already checked out in $holder; remove that worktree, or set SCRATCH_BRANCH/SCRATCH_DIR"
    fi
    if [ "$(git -C "$main" branch --show-current)" = "$OUR_BRANCH" ]; then
        say "note: $OUR_BRANCH is checked out in $main (left untouched, as it must be)"
    fi

    if [ -e "$target" ]; then
        git -C "$target" rev-parse --git-dir >/dev/null 2>&1 ||
            die "$target exists and is not a worktree; move it aside"
        say "reusing worktree $target"
        if [ -d "$target/.git/rebase-merge" ] || [ -d "$target/.git/rebase-apply" ] ||
           ! git -C "$target" diff --quiet || ! git -C "$target" diff --cached --quiet; then
            die "$target is mid-operation or carries uncommitted work; finish or remove it by hand (this script never resets)"
        fi
        branch="$(git -C "$target" branch --show-current)"
        if [ "$(git -C "$target" rev-list --count "$ours" ^HEAD)" = "0" ]; then
            say "$branch already holds every commit of $OUR_BRANCH; nothing to replay"
            say "worktree: $target  branch: $branch  commit: $(git -C "$target" rev-parse --short HEAD)"
            return 0
        fi
        # Nothing replayed yet, or the last replay stopped before its first commit: both are safe to retry in place,
        # because a cherry-pick that has not started leaves HEAD exactly where upstream put it.
        [ "$(git -C "$target" rev-parse HEAD)" = "$theirs" ] ||
            die "$branch is at $(git -C "$target" rev-parse --short HEAD), not at $UPSTREAM_REF; remove the worktree and re-run"
    else
        git -C "$main" worktree add --quiet "$target" -b "$branch" "$theirs" ||
            die "could not create worktree $target"
        say "created worktree $target on $branch from $UPSTREAM_REF"
    fi

    # A fresh worktree carries no submodule contents and about two dozen source-scanning tests fail without them.
    # Cloning whisper.cpp and llama.cpp from GitHub here takes minutes, and this script runs on a machine that already
    # has both: seed each URL from the main checkout so the checkout is a local copy, offline and instant. Fall back to
    # the repository's own URLs when the main worktree has not got them either.
    if [ "${SKIP_SUBMODULES:-0}" = "1" ]; then
        say "note: submodules left alone (SKIP_SUBMODULES=1); the sentinel tests will fail until they are checked out"
    else
        say "checking out submodules (seeded from $main when it has them)"
        local seed=() sub
        while IFS= read -r sub; do
            [ -n "$sub" ] || continue
            if [ -e "$main/$sub/.git" ]; then
                say "  $sub from $main/$sub"
                seed+=(-c "submodule.$sub.url=$main/$sub")
            else
                say "  $sub from its own URL"
            fi
        done < <(git -C "$target" config --file .gitmodules --get-regexp '^submodule\..*\.path$' 2>/dev/null | awk '{print $2}')
        # `protocol.file.allow=always` is what lets a local path be cloned at all; git refuses `file` by default since
        # the 2022 submodule advisory. The path is this machine's own checkout, not something fetched.
        git -C "$target" -c protocol.file.allow=always "${seed[@]}" submodule update --init --recursive \
            >/tmp/upstream-sync-submodules.log 2>&1 ||
            say "note: submodule checkout failed (see /tmp/upstream-sync-submodules.log); the sentinel tests will fail"
    fi

    say "replaying $OUR_BRANCH's commits ($(git rev-list --count "$base..$ours")) onto $(git rev-parse --short "$theirs")"
    # One line per commit would fill a screen; the log keeps them and the verdict line says how far it got.
    if git -C "$target" cherry-pick "$base..$ours" >/tmp/upstream-sync-cherry-pick.log 2>&1; then
        say "replay clean: $ahead commits, no conflicts"
        say "  $(tail -1 /tmp/upstream-sync-cherry-pick.log)"
    else
        say ""
        say "STOPPED at a conflict — nothing was guessed and nothing was reset."
        say "commit that stopped: $(git -C "$target" log -1 --format='%h %s' CHERRY_PICK_HEAD 2>/dev/null | cut -c1-90)"
        sed -n '/CONFLICT/p;/error: could not apply/p' /tmp/upstream-sync-cherry-pick.log | sed 's/^/  /' 
        say ""
        say "conflicted files:"
        git -C "$target" diff --name-only --diff-filter=U | sed 's/^/  /'
        say ""
        say "Resolve them in $target (UPSTREAMING.md §2.4), then:"
        say "  git -C $target add <file> && git -C $target cherry-pick --continue"
        say "  $0 --verify $target"
        return 1
    fi

    say "worktree: $target  branch: $branch  commit: $(git -C "$target" rev-parse HEAD)"
    return 0
}

# ---------------------------------------------------------------------------------------------------------------
# The verification
# ---------------------------------------------------------------------------------------------------------------

gradlew() {
    local dir="$1"; shift
    ( cd "$dir" && ./gradlew "$@" )
}

# Size and dex of the debug APK, per flag state, appended to a line of the verdict.
apk_facts() {
    local dir="$1" apk size hits unzipped
    apk="$(ls "$dir"/androidApp/build/outputs/apk/debug/*.apk 2>/dev/null | head -1)"
    if [ -z "$apk" ]; then
        printf 'no APK'
        return
    fi
    size="$(stat -c %s "$apk")"
    unzipped="$(mktemp -d)"
    unzip -o -q "$apk" 'classes*.dex' -d "$unzipped" 2>/dev/null || true
    # `strings` then a count, because a dex is one long line to grep and would answer 1 for any number of hits.
    hits="$(strings "$unzipped"/classes*.dex 2>/dev/null | grep -c "$VOICE_PACKAGE" || true)"
    rm -rf "$unzipped"
    printf '%s bytes, %s %s hits' "$size" "${hits:-0}" "$VOICE_PACKAGE"
}

verify() {
    local dir="$1" status=0
    [ -d "$dir" ] || die "no such directory: $dir"
    ( cd "$dir" && ./gradlew --version >/dev/null 2>&1 ) || die "no gradlew in $dir"

    rule
    say "verifying $(git -C "$dir" rev-parse --short HEAD) in $dir"
    rule

    say "[1/4] host suites, all five feature modules (--rerun-tasks)"
    if gradlew "$dir" "${TEST_TASKS[@]}" --rerun-tasks > /tmp/upstream-sync-tests.log 2>&1; then
        say "      PASS"
    else
        say "      FAIL — failing tests:"
        grep -E '^[^ ]+ > .* FAILED$' /tmp/upstream-sync-tests.log | sed 's/^/        /'
        status=1
    fi
    say "      $(
        python3 - "$dir" <<'PY'
import glob, sys, xml.etree.ElementTree as ET
total = failures = 0
for path in glob.glob(f'{sys.argv[1]}/**/build/test-results/testAndroidHostTest/*.xml', recursive=True):
    root = ET.parse(path).getroot()
    total += int(root.get('tests', 0)); failures += int(root.get('failures', 0)) + int(root.get('errors', 0))
print(f'{total} tests, {failures} failed')
PY
    )"

    say "[2/4] androidApp host tests (theme, guardrail and sentinel checks)"
    if gradlew "$dir" :androidApp:testDebugUnitTest --rerun-tasks > /tmp/upstream-sync-apptests.log 2>&1; then
        say "      PASS"
    else
        say "      FAIL — failing tests:"
        grep -E '^[^ ]+ > .* FAILED$' /tmp/upstream-sync-apptests.log | sed 's/^/        /'
        status=1
    fi

    say "[3/4] flag off: :androidApp:assembleDebug"
    if gradlew "$dir" :androidApp:assembleDebug > /tmp/upstream-sync-build-off.log 2>&1; then
        say "      PASS — $(apk_facts "$dir")"
    else
        say "      FAIL — see /tmp/upstream-sync-build-off.log"
        status=1
    fi

    say "[4/4] flag on:  :androidApp:assembleDebug -PvoiceMqttEnabled=true"
    if gradlew "$dir" :androidApp:assembleDebug -PvoiceMqttEnabled=true > /tmp/upstream-sync-build-on.log 2>&1; then
        say "      PASS — $(apk_facts "$dir")"
    else
        say "      FAIL — see /tmp/upstream-sync-build-on.log"
        status=1
    fi

    rule
    if [ "$status" -eq 0 ]; then
        say "VERDICT: GREEN — $(git -C "$dir" rev-parse --short HEAD) builds both ways and every host suite passes."
    else
        say "VERDICT: RED — see the lines above; nothing was skipped to get here."
    fi
    rule
    return "$status"
}

# ---------------------------------------------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------------------------------------------

cd "$(repo_root)" || exit 1

case "${1:---report}" in
    -h|--help) usage ;;
    --report)  report ;;
    --rebase)  git fetch --quiet origin || say "note: fetch failed; reporting on the refs we have"
               report
               rule
               rebase ;;
    --verify) [ -n "${2:-}" ] || die "--verify needs the worktree path"
               verify "$2" ;;
    --all)     git fetch --quiet origin || say "note: fetch failed; reporting on the refs we have"
               report
               rule
               rebase || exit $?
               "$0" --verify "$(scratch_dir)" ;;
    *)         die "unknown argument $1 (try --help)" ;;
esac
