#!/bin/bash
# Generates the lab's two Redis passwords and the ACL file that holds only their SHA-256 hashes,
# into .secrets/redis (ignored by git; not under build/, which "./gradlew clean" deletes while
# Redis still reads the files). Refuses to overwrite existing files unless given --force.
set -euo pipefail
target=".secrets/redis"
if [[ -e "${target}/users.acl" && "${1:-}" != "--force" ]]; then
  echo "error: ${target}/users.acl exists; pass --force to make new passwords" >&2
  exit 1
fi
mkdir -p "${target}"
for user in app ops; do
  openssl rand -hex 24 > "${target}/${user}.password"
done
hash_of() { printf '%s' "$(cat "${target}/$1.password")" | shasum -a 256 | cut -d ' ' -f 1; }
{
  # No anonymous access at all.
  echo "user default off"
  # The session service: the commands of S1 to S5 (SessionAccessPattern) on session keys only.
  # SCAN and KEYS are not granted, so a scan of the keyspace fails with NOPERM. HELLO, which
  # signs a Lettuce connection in, needs no grant; CLIENT SETINFO, which Lettuce sends next, is
  # granted only so that every connection does not add a NOPERM error to Redis's error counts.
  echo "user seatwave_sessions on #$(hash_of app) resetkeys ~seatwave:session:* ~seatwave:fan-sessions:* resetchannels -@all +get +set +del +hset +hdel +hlen +hkeys +hpexpire +client|setinfo"
  # Monitoring: INFO, and PING for the health check; no key at all.
  echo "user seatwave_ops on #$(hash_of ops) resetkeys resetchannels -@all +info +ping +client|setinfo"
} > "${target}/users.acl"
# The container's redis user must read the files through the read-only mount; the folder is
# ignored by git, and the passwords are throwaway values for the lab only.
chmod 0644 "${target}/users.acl" "${target}/app.password" "${target}/ops.password"
echo "wrote ${target}/users.acl and two password files"
