# Third-party notices

OpenService's original application code is offered under the root [MIT license](LICENSE). That license does not replace the licenses of third-party components.

## Source-distributed tooling

`mvnw` and `mvnw.cmd` are Apache Maven Wrapper 3.3.4 scripts. Their original headers are retained. The complete [Apache License 2.0](licenses/Apache-2.0.txt) and upstream [Maven Wrapper NOTICE](licenses/maven-wrapper-NOTICE.txt) accompany this repository. The wrapper downloads Maven; its version and distribution checksum are pinned in `.mvn/wrapper/maven-wrapper.properties`.

The license and notice were retrieved from the Apache Maven Wrapper `maven-wrapper-3.3.4` tag on 2026-11-14; trailing whitespace in the notice was normalized without changing its text:

- https://github.com/apache/maven-wrapper/blob/maven-wrapper-3.3.4/LICENSE
- https://github.com/apache/maven-wrapper/blob/maven-wrapper-3.3.4/NOTICE

## Resolved application dependencies

[The runtime license inventory](docs/runtime-licenses.json) records the 46 runtime/repackaging artifacts inspected for the Java build, with license declarations from their published Maven POMs and parent POMs. Bouncy Castle's notice was extracted from its shipped `org.bouncycastle.LICENSE` class; a [copy is included](licenses/Bouncy-Castle.txt). Spring Boot's repackaging tools use the project's Apache 2.0 license.

Most dependencies declare Apache 2.0. SLF4J and Checker Framework annotations declare MIT; PostgreSQL JDBC declares BSD-2-Clause. Logback declares EPL-2.0 / LGPL-2.1 alternatives, and Jakarta Annotations declares EPL-2.0 / GPL2 with Classpath Exception alternatives. The inventory preserves those declarations rather than assigning the application's MIT license to dependencies. Consult the actual component license for its options and obligations.

Dependencies are resolved from Maven repositories; their source is not vendored in this repository. A packaged Spring Boot JAR contains nested dependency JARs with their own metadata and available notices. Binary distributors must preserve applicable notices and satisfy the selected dependency licenses, including any source availability obligations. A POM inventory is evidence about declarations, not a legal clearance or a complete binary-distribution compliance audit.

The browser coverage converter is development-only. Its exact `v8-to-istanbul` dependency tree, package licenses and integrity hashes are recorded in `tools/browser-coverage/package-lock.json`; `node_modules` is excluded from source publication and application packaging.
