#!/usr/bin/env bash
# Persistent TEST signing identity. Generated once, kept in a DRAFT release
# (tag: test-signing, visible only to users with push access) and reused by every later build.
# Needs a token with contents: write. Never use this key for official releases.
set -euo pipefail
sign_dir="${RUNNER_TEMP:?}/upstream-signing"
mkdir -p "$sign_dir"
repo="${GITHUB_REPOSITORY:?}"
tag=test-signing

# A listing failure must stop the build, never silently create a replacement key.
release_id=$(gh api --paginate "repos/$repo/releases?per_page=100" \
    --jq ".[] | select(.tag_name==\"$tag\") | .id" | head -n 1)

download_asset() {
    local name="$1" asset_id
    asset_id=$(gh api "repos/$repo/releases/$release_id" --jq ".assets[] | select(.name==\"$name\") | .id")
    [ -n "$asset_id" ] || { echo "::error::Asset $name missing in release $tag" >&2; exit 1; }
    gh api -H 'Accept: application/octet-stream' "repos/$repo/releases/assets/$asset_id" > "$sign_dir/$name"
}

if [ -n "$release_id" ]; then
    echo "Reusing stored test signing identity"
    download_asset upstream.p12
    download_asset password.txt
else
    echo "No stored identity found, generating one"
    signing_pass=$(openssl rand -hex 24)
    echo "::add-mask::$signing_pass"
    keytool -genkeypair -noprompt -storetype PKCS12 -keystore "$sign_dir/upstream.p12" \
        -alias upstream -keyalg RSA -keysize 3072 -validity 10000 \
        -dname 'CN=Winlator Ludashi Test' -storepass "$signing_pass" -keypass "$signing_pass"
    printf '%s' "$signing_pass" > "$sign_dir/password.txt"
    release_id=$(gh api "repos/$repo/releases" -f tag_name="$tag" -F draft=true \
        -f name='Private TEST signing identity - do not publish' \
        -f body='Test keystore reused by CI. Keep both assets.' --jq .id)
    for name in upstream.p12 password.txt; do
        curl -fsS -X POST -H "Authorization: Bearer $GH_TOKEN" \
            -H 'Content-Type: application/octet-stream' --data-binary "@$sign_dir/$name" \
            "https://uploads.github.com/repos/$repo/releases/$release_id/assets?name=$name" >/dev/null
    done
fi

LUDASHI_STORE_PASSWORD=$(cat "$sign_dir/password.txt")
echo "::add-mask::$LUDASHI_STORE_PASSWORD"
keytool -list -keystore "$sign_dir/upstream.p12" -storepass "$LUDASHI_STORE_PASSWORD" -alias upstream >/dev/null
{
    echo "LUDASHI_KEYSTORE_PATH=$sign_dir/upstream.p12"
    echo "LUDASHI_STORE_PASSWORD=$LUDASHI_STORE_PASSWORD"
    echo "LUDASHI_SIGNING_MODE=test"
} >> "${GITHUB_ENV:?}"
