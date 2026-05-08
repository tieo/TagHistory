# secrets/

Sops-encrypted release-signing material. Only `*.enc.*` files are tracked.
Plaintext lands here briefly during decrypt and is wiped after use.

## What's here

| file | what it is |
|---|---|
| `release-keystore.jks.enc.jks` | RSA-4096 JKS used for signing release APKs |
| `keystore-password.enc.txt` | password for both store and key alias `upload` |

The same secrets are mirrored in the `Android Build Release` GitHub Actions
environment as `SIGNING_KEY`, `KEY_STORE_PASSWORD`, `KEY_PASSWORD`. CI uses
those; this directory is the local-source-of-truth backup.

## Decrypt for local use

```bash
sops -d secrets/release-keystore.jks.enc.jks > /tmp/release-keystore.jks
sops -d secrets/keystore-password.enc.txt
shred -u /tmp/release-keystore.jks  # when done
```

## Re-upload to GitHub after rotating

```bash
PASS=$(sops -d secrets/keystore-password.enc.txt)
sops -d secrets/release-keystore.jks.enc.jks | base64 -w0 \
    | gh secret set SIGNING_KEY --env "Android Build Release"
printf '%s' "$PASS" | gh secret set KEY_STORE_PASSWORD --env "Android Build Release"
printf '%s' "$PASS" | gh secret set KEY_PASSWORD --env "Android Build Release"
```

## Add a recipient (new dev)

Append the new age public key to `creation_rules[0].age` in `.sops.yaml`,
then `sops updatekeys secrets/*.enc.*`.
