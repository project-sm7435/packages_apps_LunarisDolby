# Offline AutoEQ database

The APK contains a single `database.zip` archive.

Archive layout:
- `metadata.json` — server version metadata.
- `index.json.gz` — searchable profile catalogue.
- `profiles/<id>.json.gz` — individual AutoEQ profiles.

The profile payloads are already gzip-compressed, so the outer database ZIP stores
them without another compression pass.

Normal AutoEQ operation is completely offline. The explicit Sync action may
download the upstream repository, validate every profile, build a new database
archive, and atomically replace the installed database.
