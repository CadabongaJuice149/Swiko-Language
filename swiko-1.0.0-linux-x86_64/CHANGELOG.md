# Changelog

## 1.0.0

- Check all branches and function bodies before execution.
- Require nullable values to be coalesced before arithmetic or boolean use.
- Require non-Unit functions to return on all paths.
- Generate actual standalone and library Swift or Kotlin source from either frontend.
- Expose `swikoRun` and `swikoMain` with fresh state for host integration.
- Add checking, eval, stdin, source emission, library mode, and consistent CLI statuses.
- Strictly decode UTF-8, accept initial BOM, normalize line endings, and standardize scalar columns.
- Bound source, token, string, and output growth before allocation.
- Statically link the Swift standard library in the native Linux distribution.
- Add a Kotlin release tool, checksums, source hashes, build evidence, distributions,
  extracted-archive validation, and cross-target compiler integration tests.

Programs relying on nullable arithmetic or ignored errors in skipped branches
must be updated. Forward references in function bodies are now rejected.

## 0.1

Two matching interpreters implemented runtime type checks, primitive values,
functions, recursion, control flow, nullable bindings, checked integer arithmetic,
and 62 shared conformance cases.
