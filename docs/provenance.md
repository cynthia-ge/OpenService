# Provenance and publication evidence

Reviewed 2026-11-14. This is a bounded technical review, not certification of a formal clean-room process or a legal opinion.

## Current material

- The Java application, templates and tests were developed in this repository to implement documented service-management behavior. ServiceNow documentation supplies API contracts and capability comparisons; it does not supply application source code or bundled product assets.
- The application screenshots are generated from OpenService by its Chromium browser tests using synthetic records. No ServiceNow screenshot, logo or marketing image was identified in the current asset set inspected for publication.
- `src/test/resources/contracts.json.gz` contains 113 local behavioral fixtures. 101 scenario names correlate with the earlier local Python implementation's tests in this repository history; 11 attachment scenarios came from a working local baseline without a matching committed test function, and one later scenario is documented by the OS-025 change. An exact generator/revision is not retained for every fixture. These are regression inputs, not licensed vendor-instance captures or proof of ServiceNow conformance.
- `MigrationSource` is an original synthetic HTTP fixture. Passing against it proves OpenService's local migration contract. No live ServiceNow instance or customer dataset was used to verify this release.
- Maven Wrapper scripts are third-party material with original headers and accompanying license/NOTICE files. See [third-party notices](../THIRD_PARTY_NOTICES.md). Runtime libraries retain their separate licenses.

## Review scope and limits

The pre-publication review inspected current source/assets, compressed fixture contents, dependency declarations and reachable Git history. An initial scan covered 624 unique reachable blobs (approximately 16 MB), including historical Python implementation files, for common private-key/token and confidential/proprietary markers; none of those patterns matched. A follow-up scan covered 142 current publication files and the same 624 reachable blobs, with no matches for those credential patterns. The final staged content must still be checked before publication. This does not prove that every possible secret pattern or provenance issue is absent.

No formal separation between specification authors and implementers, exhaustive comparison with proprietary source, or legal review has been established. Renaming code, rewriting it in another language, an AI rewrite, a permissive project license, or removing history would not establish originality or remove third-party obligations. Preserve attribution and evidence rather than concealing origins.

The exact fixture-generator provenance gap remains documented above. Future fixtures should retain their originating test/revision and whether evidence is synthetic or from an authorized reference instance. Public documentation and product names should only identify intended interoperability; they must not imply vendor sponsorship or endorsement.
