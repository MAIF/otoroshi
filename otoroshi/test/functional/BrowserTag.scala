package functional

import org.scalatest.Tag

/**
 * ScalaTest tag for specs/tests that drive a real Chromium browser through
 * playwright-java (`com.microsoft.playwright`).
 *
 * These tests are heavy and flaky in CI (they spawn a native browser + node
 * driver). They are therefore:
 *   - EXCLUDED from the fast server test run (`sbt testOnly ... -- -l Browser`),
 *   - run on their own in the dedicated "Server Browser Tests" workflow
 *     (`sbt testOnly functional.PluginsTestSpec -- -n Browser`).
 */
object Browser extends Tag("Browser")
