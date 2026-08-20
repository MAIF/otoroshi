package functional

import org.scalatest.Tag

/**
 * ScalaTest tag for specs/tests that spin up real containers through
 * testcontainers (`org.testcontainers`).
 *
 * These tests pull docker images and start containers, which is slow and
 * requires a working docker daemon. They are therefore EXCLUDED from the fast
 * server test run (`sbt testOnly ... -- -l Browser -l Docker`).
 */
object Docker extends Tag("Docker")
