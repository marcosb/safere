// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/**
 * Portable compile/match core for [PlatformPattern] on non-JVM targets.
 *
 * Compiles Parser → Compiler → Prog, then selects among OnePass, BitState, DFA reject, start /
 * reject accelerators, and Nfa — engines already in commonMain, without the JVM Matcher façade.
 */
internal object PlatformRegexEngine {
  class Compiled(
    val regex: String,
    val flags: Int,
    val prog: Prog,
    /** Capturing groups including group 0. */
    val numCaptures: Int,
    val namedGroups: Map<String, Int>,
    val onePass: OnePass?,
    val startAccelerator: StringStartAccelerator?,
    val rejectPrefilter: RejectPrefilter?,
    val unixLines: Boolean,
  ) {
    /** Capturing groups excluding group 0 (Matcher.groupCount semantics). */
    val groupCount: Int
      get() = maxOf(0, numCaptures - 1)

    fun groupIndex(name: String): Int =
      namedGroups[name] ?: throw IllegalArgumentException("No group with name <$name>")
  }

  fun compile(regex: String, flags: Int = 0): Compiled {
    PatternFlagMapping.validateFlags(flags)
    val effective = PatternFlagMapping.effectiveFlags(flags)
    val parseFlags = PatternFlagMapping.toParseFlags(effective)
    val re =
      Parser.parse(regex, parseFlags)
        ?: throw PatternSyntaxExceptions.create("error parsing regexp", regex, -1)
    val analysis = AstAnalysis.analyze(re)
    val prepared =
      Compiler.prepare(re)
        ?: throw PatternSyntaxExceptions.create("compiled program too large", regex, -1)
    val prog =
      Compiler.compile(prepared, false)
        ?: throw PatternSyntaxExceptions.create("compiled program too large", regex, -1)
    val unixLines = (effective and PatternFlagMapping.UNIX_LINES) != 0
    prog.setUnixLines(unixLines)

    val onePassEligible =
      !analysis.hasLazy
        && !analysis.hasNullableAlt
        && prog.numLoopRegs() == 0
        && !prog.hasGraphemeSemantics()
        && prog.numCaptures() <= OnePass.MAX_CAPTURE_GROUPS
    val onePass = if (onePassEligible) OnePass.build(prog) else null

    val multiAnchor = MultiAnchorCompiler.compile(re, effective)
    val startAccelerator = StringStartAccelerator.create(multiAnchor, prog.hasWordBoundary())
    val rejectPrefilter = RejectPrefilter.create(multiAnchor)

    return Compiled(
      regex,
      effective,
      prog,
      prepared.numCaptures,
      analysis.namedGroups,
      onePass,
      startAccelerator,
      rejectPrefilter,
      unixLines,
    )
  }

  fun quote(s: String): String = PatternFlagMapping.quote(s)

  fun matches(compiled: Compiled, text: String, regionStart: Int, regionEnd: Int): IntArray? =
    search(
      compiled,
      text,
      startPos = regionStart,
      regionStart = regionStart,
      regionEnd = regionEnd,
      anchored = true,
      longest = true,
      endMatch = true,
    )

  fun lookingAt(
    compiled: Compiled,
    text: String,
    regionStart: Int,
    regionEnd: Int,
  ): IntArray? =
    search(
      compiled,
      text,
      startPos = regionStart,
      regionStart = regionStart,
      regionEnd = regionEnd,
      anchored = true,
      longest = false,
      endMatch = false,
    )

  fun find(
    compiled: Compiled,
    text: String,
    startPos: Int,
    regionStart: Int,
    regionEnd: Int,
  ): IntArray? {
    if (startPos < regionStart || startPos > regionEnd) {
      throw IndexOutOfBoundsException("start=$startPos region=[$regionStart, $regionEnd)")
    }
    return search(
      compiled,
      text,
      startPos = startPos,
      regionStart = regionStart,
      regionEnd = regionEnd,
      anchored = false,
      longest = false,
      endMatch = false,
    )
  }

  private fun search(
    compiled: Compiled,
    text: String,
    startPos: Int,
    regionStart: Int,
    regionEnd: Int,
    anchored: Boolean,
    longest: Boolean,
    endMatch: Boolean,
  ): IntArray? {
    val region = StringRegionInputScanner(text, regionStart, regionEnd)
    val relativeStart = startPos - regionStart
    val nsubmatch = maxOf(1, compiled.numCaptures)
    val options = EnginePathOptions.allEnabled()

    if (compiled.rejectPrefilter != null
      && compiled.rejectPrefilter.canReject(text, region, relativeStart, options)
    ) {
      return null
    }

    if (anchored && compiled.onePass != null) {
      val caps =
        compiled.onePass.search(region, relativeStart, region.length, endMatch, nsubmatch)
      return toAbsolute(caps, regionStart)
    }

    val window = regionEnd - startPos
    val maxBit = BitState.maxTextSize(compiled.prog)
    if (maxBit >= 0 && window <= maxBit) {
      val bitResult =
        BitState.search(
          /* cached = */ null,
          compiled.prog,
          region,
          relativeStart,
          region.length,
          anchored,
          longest,
          endMatch,
          nsubmatch,
          /* resultBuffer = */ null,
        )
      if (bitResult != null) {
        return toAbsolute(bitResult, regionStart)
      }
      return null
    }

    if (!anchored) {
      val dfa =
        Dfa.search(
          compiled.prog,
          region,
          relativeStart,
          anchored = false,
          longest = false,
          /* maxStates = */ 10_000,
        )
      if (dfa != null && !dfa.matched) {
        return null
      }
    }

    var from = relativeStart
    val accelerator = compiled.startAccelerator
    if (!anchored && accelerator != null) {
      while (true) {
        val candidate =
          StringStartAccelerator.findNextCandidate(
            accelerator,
            region,
            from,
            compiled.unixLines,
          )
        if (candidate < 0) {
          return null
        }
        if (compiled.rejectPrefilter != null
          && compiled.rejectPrefilter.canReject(text, region, candidate, options)
        ) {
          from = candidate + 1
          continue
        }
        val caps =
          nfaSearch(compiled.prog, region, candidate, nsubmatch, anchored = true, endMatch = false)
        if (caps != null) {
          return toAbsolute(caps, regionStart)
        }
        from = candidate + 1
        if (from > region.length) {
          return null
        }
      }
    }

    val caps =
      nfaSearch(
        compiled.prog,
        region,
        relativeStart,
        nsubmatch,
        anchored = anchored,
        endMatch = endMatch,
        longest = longest,
      )
    return toAbsolute(caps, regionStart)
  }

  private fun nfaSearch(
    prog: Prog,
    region: InputScanner,
    startPos: Int,
    nsubmatch: Int,
    anchored: Boolean,
    endMatch: Boolean,
    longest: Boolean = false,
  ): IntArray? {
    val anchor = if (anchored) Nfa.Anchor.ANCHORED else Nfa.Anchor.UNANCHORED
    val kind =
      when {
        endMatch -> Nfa.MatchKind.FULL_MATCH
        longest -> Nfa.MatchKind.LONGEST_MATCH
        else -> Nfa.MatchKind.FIRST_MATCH
      }
    return Nfa.search(
      prog,
      region,
      startPos,
      region.length,
      region.length,
      0,
      anchor,
      kind,
      nsubmatch,
      null,
    )
  }

  private fun toAbsolute(caps: IntArray?, regionStart: Int): IntArray? {
    if (caps == null || regionStart == 0) {
      return caps
    }
    var i = 0
    while (i < caps.size) {
      if (caps[i] >= 0) {
        caps[i] += regionStart
      }
      i++
    }
    return caps
  }
}
