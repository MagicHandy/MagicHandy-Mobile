# Desktop fixture export

Run `./tools/port-fixtures/export.ps1` from this checkout in PowerShell. To verify reproducibility independently, pass `-Output '.work/fixture-repeat'` and compare the two manifest hashes. The script checks the snapshot revision, copies its Go sources to the snapshot's `cmd/port-fixtures`, and uses the workspace Go caches. No exporter is bundled in the app.

The bounded-ramp subset uses the first seven deprecated atlas shapes at 10, 25 and 43 percent from `cmd/motion-atlas`. Their authored coordinates are inert sampler inputs. Expected frames come from `motion.ReviewMotionOutput`. The catalog is test data only.

The three hosted fixtures run the interactive Go service and Responses encoder with a synthetic final turn, a 131072-token input context, no history and Low reasoning. An in-memory HTTP RoundTripper captures the exact body and returns a synthetic rejection. No listener, account or network access is involved. The Handy vectors come from the desktop's protocol fixtures, with RPC 715 and state encoded directly by the Go codec. Content comes from allowlisted files and in-code defaults.

The exporter canonicalizes text line endings to LF before hashing. This preserves the manifest through the mobile checkout's Git text normalization, including the desktop notice catalog and brand SVGs. RPC bytes are stored as hexadecimal strings.
