#!/usr/bin/env bash
#
# Push the release commits (and optionally the release tag) to origin/master.
#
# A release run takes ~25mn, most of it in the docker publish, and master is not frozen while it
# runs. When someone pushes a regular commit in the meantime, a plain `git push origin master` is
# rejected with "! [rejected] master -> master (fetch first)". That used to kill the whole release
# step, and with it everything that comes after (milestone, checksums, GitHub release), even though
# the artifacts were already published.
#
# So: replay the release commits on top of whatever landed, and try again a few times.
#
# usage: git-push-master.sh [tag]
#
set -uo pipefail

TAG="${1:-}"
BRANCH="${RELEASE_BRANCH:-master}"
ATTEMPTS="${PUSH_ATTEMPTS:-5}"

log() { echo "[git-push-master] $*"; }

tag_commit() { git rev-parse -q --verify "refs/tags/${TAG}^{commit}" 2> /dev/null; }

# subject of the commit the release tag points to, captured before any rebase rewrites it
tagged_subject=""
tag_moved="no"
if [ -n "${TAG}" ] && [ -n "$(tag_commit)" ]; then
  tagged_subject="$(git log -1 --format=%s "refs/tags/${TAG}^{commit}")"
fi

pushed="no"
for attempt in $(seq 1 "${ATTEMPTS}"); do

  if ! git fetch --quiet origin "${BRANCH}"; then
    log "cannot fetch origin/${BRANCH} (attempt ${attempt}/${ATTEMPTS})"
    sleep $((attempt * 5))
    continue
  fi

  if [ "$(git rev-list --count FETCH_HEAD..HEAD)" -eq 0 ]; then
    log "origin/${BRANCH} already contains every local commit, nothing to push"
    pushed="yes"
    break
  fi

  # someone pushed while we were building: replay our commits on top of theirs
  if [ "$(git rev-list --count HEAD..FETCH_HEAD)" -ne 0 ]; then
    log "origin/${BRANCH} moved ($(git rev-list --count HEAD..FETCH_HEAD) new commit(s)), rebasing the release commits on top of it"
    if ! git rebase FETCH_HEAD; then
      git rebase --abort > /dev/null 2>&1
      log "the release commits conflict with origin/${BRANCH}"
      echo "::error::release commits conflict with origin/${BRANCH}, they have to be pushed by hand"
      exit 1
    fi
    # the rebase rewrote those commits, so the tag now points to a commit that is not on the branch
    if [ -n "${tagged_subject}" ]; then
      new_commit="$(git log -1 --format=%H --fixed-strings --grep="${tagged_subject}")"
      if [ -n "${new_commit}" ] && [ "${new_commit}" != "$(tag_commit)" ]; then
        log "moving tag ${TAG} to the rebased commit ${new_commit}"
        git tag -f -am "Release Otoroshi version ${TAG#v}" "${TAG}" "${new_commit}"
        tag_moved="yes"
      fi
    fi
  fi

  if git push origin "HEAD:${BRANCH}"; then
    log "pushed to origin/${BRANCH}"
    pushed="yes"
    break
  fi

  log "push to origin/${BRANCH} rejected (concurrent push ?), attempt ${attempt}/${ATTEMPTS}"
  sleep $((attempt * 5))
done

if [ "${pushed}" != "yes" ]; then
  echo "::error::unable to push the release commits to origin/${BRANCH} after ${ATTEMPTS} attempts"
  exit 1
fi

if [ -n "${TAG}" ] && [ -n "$(tag_commit)" ]; then
  if [ "${tag_moved}" = "yes" ]; then
    # the tag was moved onto the rebased commit, a previous push of it (if any) has to be overwritten
    git push --force origin "refs/tags/${TAG}"
  else
    git push origin "refs/tags/${TAG}"
  fi || {
    echo "::error::unable to push tag ${TAG} to origin"
    exit 1
  }
  log "pushed tag ${TAG}"
fi
