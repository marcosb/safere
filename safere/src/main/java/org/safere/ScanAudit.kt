// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.safere.ScanAudit.ENABLED
import org.safere.ScanAudit.record


/**
 * Package-private record of which scan kernel each dispatch site chose, for tests.
 *
 *
 * Every vector dispatch ladder sizes its crossover decision by the length of the region it is
 * about to scan. Passing the length of the whole input instead defeats the thresholds, because one
 * long input then selects a provider that serves every narrow probe issued against it. That defect
 * is invisible to result-based tests: the answer stays correct and only the cost changes. This
 * class makes the choice observable so a test can assert on it directly.
 *
 *
 * Sites record the tier that *ran*, not the provider's admission decision.
 * [VectorScanProviders.providerFor] returning `null` is often correct, and at least one past
 * defect was a caller mishandling a correct `null`, so an audit taken inside `providerFor` could
 * not have seen it.
 *
 * @see ScanEvent
 */
internal object ScanAudit {
    /** System property that turns dispatch recording on.  */
    const val PROPERTY: String = "org.safere.testing.scanAudit"

    /**
     * Whether dispatch recording is on.
     *
     *
     * Read once in `<clinit>` and held in a `static final boolean`, so C2 folds it to
     * a constant after class initialization and every [record] call collapses to nothing. This
     * deliberately does not follow `WorkCounterConfig.ENABLED`, which is generated at build
     * time behind a Maven profile: that machinery exists because a work counter fires once per byte
     * scanned, where even a folded branch in the interpreter matters. A dispatch event fires once per
     * scan call, three orders of magnitude less often.
     */
    val ENABLED: Boolean = java.lang.Boolean.getBoolean(PROPERTY)

    private val LOG = ThreadLocal<MutableList<ScanEvent>?>()

    /**
     * Runs `task` and returns the dispatch events it recorded, in the order they occurred.
     *
     * @throws IllegalStateException if recording is off, or if work counting is on
     */
    fun captureForTesting(task: Runnable): List<ScanEvent> {
        check(ENABLED) { "ScanAudit is disabled; run tests with -D" + PROPERTY }
        check(!WorkCounterConfig.ENABLED) {
            "ScanAudit cannot observe dispatch under -Pwork-counters, which bypasses every ladder"
        }
        val previous = LOG.get()
        val current: MutableList<ScanEvent> = ArrayList()
        LOG.set(current)
        try {
            task.run()
            validateDispatchPairs(current)
            return java.util.List.copyOf(current)
        } finally {
            if (previous == null) {
                LOG.remove()
            } else {
                LOG.set(previous)
            }
        }
    }

    /**
     * Validates that `events` strictly alternates between [ScanPath.CONSULTED] and an
     * execution path of matching kind and window length.
     *
     * @throws IllegalStateException if an event is unpaired, consecutive consultations occur, or the
     * kind or window length of a pair diverges
     */
    fun validateDispatchPairs(events: MutableList<ScanEvent>) {
        val size: Int = events.size
        check((size and 1) == 0) {
            ("ScanAudit captured an odd number of events ("
                    + size
                    + "), violating the 1-to-1 (CONSULTED, path) pairing invariant: "
                    + events)
        }
        var i = 0
        while (i < size) {
            val consultation = events[i]
            val pathEvent = events[i + 1]
            check(consultation.path == ScanPath.CONSULTED) {
                ("ScanAudit expected ScanPath.CONSULTED at index "
                        + i
                        + " but found "
                        + consultation
                        + " in "
                        + events)
            }
            check(pathEvent.path != ScanPath.CONSULTED) {
                ("ScanAudit expected execution path at index "
                        + (i + 1)
                        + " but found consecutive CONSULTED event "
                        + pathEvent
                        + " in "
                        + events)
            }
            check(
                consultation.kind == pathEvent.kind
                        && consultation.windowLength == pathEvent.windowLength
            ) {
                ("ScanAudit dispatch pair mismatch at index "
                        + i
                        + ": consultation "
                        + consultation
                        + " does not match path "
                        + pathEvent
                        + " in "
                        + events)
            }
            i += 2
        }
    }

    /**
     * Records that a dispatch site ran `path` over a window of `windowLength` bytes.
     *
     *
     * The guard is inside the method rather than at each call site so that a ladder reads as one
     * statement per exit. Inlining folds the whole call away once [ENABLED] is known false.
     */
    fun record(kind: ScanKind?, direction: ScanDirection?, windowLength: Int, path: ScanPath?) {
        if (!ENABLED) {
            return
        }
        val log = LOG.get()
        if (log != null) {
            log.add(ScanEvent(kind, direction, windowLength, path))
        }
    }

    /**
     * Records that [VectorScanProviders.providerFor] was asked about a window.
     *
     *
     * This is the one event no call site has to opt into, which is what makes the audit resistant
     * to rot: a dispatch ladder added later and never instrumented still shows up in a capture, as a
     * consultation with no path recorded against it.
     */
    fun recordConsultation(kind: ScanKind?, windowLength: Int) {
        record(kind, ScanDirection.UNSPECIFIED, windowLength, ScanPath.CONSULTED)
    }
}
