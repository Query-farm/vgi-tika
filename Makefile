# vgi-tika VGI worker — build, unit-test, and SQL E2E targets.
#
# Usage:
#   make build      # fat JAR -> build/libs/vgi-tika-<ver>-all.jar
#   make test       # JUnit + SQL E2E (the full suite)
#   make test-unit  # ./gradlew test (JUnit only)
#   make test-sql   # shadowJar + haybarn-unittest over test/sql/*
#
# SQL E2E needs haybarn-unittest on PATH:
#   uv tool install haybarn-unittest      # binary at ~/.local/bin/haybarn-unittest
#   export PATH="$$HOME/.local/bin:$$PATH"

GRADLE        ?= ./gradlew
UNITTEST      ?= haybarn-unittest
TEST_DIR       = .
TEST_PATTERN   = test/sql/*

# The fat JAR is a self-contained VGI worker; its manifest sets Add-Opens so a
# bare `java -jar` works as a LOCATION (no extra --add-opens needed).
JAR_GLOB       = build/libs/vgi-tika-*-all.jar

.PHONY: test test-unit test-sql build fixtures clean

build:
	$(GRADLE) --no-daemon shadowJar

# Regenerate the committed SQL fixtures from the PDFBox/POI builders.
fixtures:
	$(GRADLE) --no-daemon generateSqlFixtures

test-unit:
	$(GRADLE) --no-daemon test

# SQL E2E: build the fat JAR, (re)generate fixtures, then run the .test files
# through DuckDB via haybarn-unittest with the worker as the VGI LOCATION.
test-sql: build fixtures
	@command -v $(UNITTEST) >/dev/null 2>&1 || { \
		echo "ERROR: $(UNITTEST) not found on PATH." >&2; \
		echo "  Install: uv tool install haybarn-unittest" >&2; \
		echo "  Then:    export PATH=\"\$$HOME/.local/bin:\$$PATH\"" >&2; \
		exit 1; \
	}
	@JAR="$$(ls $(JAR_GLOB) | head -1)"; \
	if [ -z "$$JAR" ]; then echo "ERROR: fat JAR not built ($(JAR_GLOB))" >&2; exit 1; fi; \
	echo "Worker JAR: $$JAR"; \
	VGI_TIKA_WORKER="java -jar $$(pwd)/$$JAR" \
		$(UNITTEST) --test-dir "$(TEST_DIR)" "$(TEST_PATTERN)"

test: test-unit test-sql

clean:
	$(GRADLE) --no-daemon clean
