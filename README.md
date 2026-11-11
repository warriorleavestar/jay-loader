# Jay Loader

A universal Minecraft mod loader (prototype): one runtime that loads Fabric, Quilt, NeoForge and Forge mods together. It does the remapping and Mixin work at bake time and is driven by a launcher through a small CLI interface.

- Platforms: vanilla/Fabric 1.21.1, NeoForge 1.21.1, Forge 1.20.1.
- Mods: Fabric and Quilt mods on every platform, through a Fabric API bridge on NeoForge/Forge. Also Forge 1.21.1 mods on NeoForge, and NeoForge 1.20.1 mods on Forge. See `docs/loader-matrix.md`.
- Launcher interface: `docs/amber-bake-interface.md` (stages `version`, `requirements`, `bake-mod`, `bake-instance`, `warm`, `gc`; JSON lines).

## Build

Windows with Git Bash, JDK 21 and Python 3 on `PATH`:

```bash
bash build.sh                        # fetches the libraries in libs.lock.json into deps/ (sha256 checked), builds build/loader
python -I tools/package.py --core-only   # build/dist/amber-ul-<version>/ + .zip
```

The bridge jars compile against the Minecraft, NeoForge and Forge jars. Those are never in this repository. `build.sh` builds the bridges only when a local test setup in `mc/` provides them; the `tools/fetch_*.py` scripts download them from the official sources for local testing. Without them, package with `--core-only`.

## Licence

PolyForm Noncommercial 1.0.0 (`LICENSE`), with a Commercial Use Exception for individuals and small teams (`COMMERCIAL-EXCEPTION.md`), except the Fabric API-derived files and the Fabric/Quilt API declarations, which are under Apache-2.0. See `THIRD_PARTY_NOTICES.md`. Versions released before this change stay under the MIT License.

Any place that still calls Jay Loader "open source" or "MIT", including older docs and release notes, is outdated: `LICENSE` and `COMMERCIAL-EXCEPTION.md` take precedence. If you find such a description, please open an issue. Minecraft is not included.
