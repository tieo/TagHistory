# Vendored Rust sources

These crates are vendored (not git submodules) so the build is reproducible
offline and patches live in-tree as plain diffs.

## Provenance

| Crate              | Upstream                                                          | Pin (as-of vendored)                                                                 | Date       |
|--------------------|-------------------------------------------------------------------|--------------------------------------------------------------------------------------|------------|
| `omnisette/`       | `nab138/apple-private-apis` (fork of dormant `SideStore/apple-private-apis`) | `65baf127f9702176b7488256fe5e01339668116a` (only `omnisette/` subtree imported)      | 2025-11-09 |
| `android-loader/`  | `Dadoum/android-loader`, branch `bigger_pages`                    | `dfa86501afca7caa23d5ce15322ac7260d857485`                                           | 2023-04-03 |
| `ottjni/`          | First-party (this repo)                                           | —                                                                                    | —          |

## Local patches applied to vendored sources

- `omnisette/Cargo.toml`: `android-loader` dependency rewritten from
  `git = ".../bigger_pages"` to `path = "../android-loader"` so the workspace
  resolves locally without network.

## Refreshing

If you need to pick up upstream changes, re-clone and diff:

    git clone https://github.com/nab138/apple-private-apis /tmp/nab138-apple-private-apis
    diff -r /tmp/nab138-apple-private-apis/omnisette rust/omnisette

Then reapply the local patch above.
