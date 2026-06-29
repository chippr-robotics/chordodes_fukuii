package com.chipprbots.ethereum.crypto

import ethereum.ckzg4844.CKZG4844JNI

/** Process-wide, one-time loader for the KZG trusted setup shared by the KZG test suites.
  *
  * The c-kzg-4844 trusted setup is a single native global. The build runs suites with `Test / parallelExecution :=
  * true`, so KzgPointEvaluationSpec and KzgCellProofsSpec can execute on concurrent threads inside the one forked test
  * JVM. If each suite independently loads the setup in `beforeAll` and frees it in `afterAll`, one suite's
  * `freeTrustedSetup()` can yank the global setup out from under another suite mid-test — surfacing as
  * `RuntimeException: Trusted Setup is not loaded`.
  *
  * The setup is immutable ceremony data and the forked JVM exits at the end of the run, so the correct, race-free
  * pattern (as in go-ethereum / besu) is to load exactly once and never free during the session.
  */
object KzgTestSetup:
  @volatile private var loaded = false

  /** Idempotently load the native library and trusted setup. Safe to call from multiple suites/threads. */
  def ensureLoaded(): Unit = synchronized {
    if !loaded then
      CKZG4844JNI.loadNativeLibrary()
      CKZG4844JNI.loadTrustedSetupFromResource("/trusted_setup.txt", classOf[CKZG4844JNI], 0L)
      loaded = true
  }
