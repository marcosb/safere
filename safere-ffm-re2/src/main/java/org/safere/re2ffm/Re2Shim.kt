// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.re2ffm

import java.lang.foreign.*
import java.lang.invoke.MethodHandle
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.*

/**
 * Low-level FFM bindings to the re2_shim native library. Each static method corresponds to a C
 * function in re2_shim.h.
 */
internal object Re2Shim {
    private val COMPILE: MethodHandle
    private val COMPILE_CASE_INSENSITIVE: MethodHandle
    private val FREE: MethodHandle
    private val OK: MethodHandle
    private val ERROR: MethodHandle
    private val NUM_CAPTURING_GROUPS: MethodHandle
    private val MATCH: MethodHandle
    private val FIND_ALL: MethodHandle
    private val REPLACE_FIRST: MethodHandle
    private val REPLACE_ALL: MethodHandle
    private val REPLACE_LITERAL: MethodHandle

    init {
        // Load the native library from the build directory or system path.
        val libPath = System.getProperty("re2shim.library.path")
        val lookup: SymbolLookup?
        if (libPath != null) {
            val osName = System.getProperty("os.name").toLowerCase(Locale.ROOT)
            val libName = if (osName.contains("mac")) "libre2_shim.dylib" else "libre2_shim.so"
            lookup = SymbolLookup.libraryLookup(Path.of(libPath, libName), Arena.global())
        } else {
            // Fall back to system library path
            System.loadLibrary("re2_shim")
            lookup = SymbolLookup.loaderLookup()
        }

        val linker = Linker.nativeLinker()

        // re2_pattern_t* re2_compile(const char* pattern, int pattern_len)
        COMPILE =
            linker.downcallHandle(
                lookup.find("re2_compile").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
            )

        // re2_pattern_t* re2_compile_case_insensitive(const char* pattern, int pattern_len)
        COMPILE_CASE_INSENSITIVE =
            linker.downcallHandle(
                lookup.find("re2_compile_case_insensitive").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
            )

        // void re2_free(re2_pattern_t* p)
        FREE =
            linker.downcallHandle(
                lookup.find("re2_free").orElseThrow(), FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
            )

        // bool re2_ok(const re2_pattern_t* p)
        OK =
            linker.downcallHandle(
                lookup.find("re2_ok").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS)
            )

        // const char* re2_error(const re2_pattern_t* p)
        ERROR =
            linker.downcallHandle(
                lookup.find("re2_error").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            )

        // int re2_num_capturing_groups(const re2_pattern_t* p)
        NUM_CAPTURING_GROUPS =
            linker.downcallHandle(
                lookup.find("re2_num_capturing_groups").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
            )

        MATCH =
            linker.downcallHandle(
                lookup.find("re2_match").orElseThrow(),
                FunctionDescriptor.of(
                    ValueLayout.JAVA_BOOLEAN,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT
                )
            )

        // int re2_replace_first(const re2_pattern_t* p, const char* text, int text_len,
        //                      const char* rewrite, int rewrite_len,
        //                      char* out_buf, int out_cap, int* out_len)
        REPLACE_FIRST =
            linker.downcallHandle(
                lookup.find("re2_replace_first").orElseThrow(),
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS
                )
            )

        // int re2_replace_all(const re2_pattern_t* p, const char* text, int text_len,
        //                    const char* rewrite, int rewrite_len,
        //                    char* out_buf, int out_cap, int* out_len)
        REPLACE_ALL =
            linker.downcallHandle(
                lookup.find("re2_replace_all").orElseThrow(),
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS
                )
            )

        // int re2_find_all(const re2_pattern_t* p, const char* text, int text_len,
        //                  int32_t* matches_out, int max_matches)
        FIND_ALL =
            linker.downcallHandle(
                lookup.find("re2_find_all").orElseThrow(),
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT
                )
            )

        // void re2_replace_literal(const uint8_t* input, int input_len,
        //                          const int* groups, int groups_count,
        //                          const uint8_t* replacement, int replacement_len,
        //                          uint8_t* output)
        REPLACE_LITERAL =
            linker.downcallHandle(
                lookup.find("re2_replace_literal").orElseThrow(),
                FunctionDescriptor.ofVoid(
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS
                )
            )
    }

    fun compile(pattern: MemorySegment?, patternLen: Int): MemorySegment? {
        try {
            return COMPILE.invokeExact(pattern, patternLen) as MemorySegment?
        } catch (t: Throwable) {
            throw AssertionError("FFM call failed", t)
        }
    }

    fun compileCaseInsensitive(pattern: MemorySegment?, patternLen: Int): MemorySegment? {
        try {
            return COMPILE_CASE_INSENSITIVE.invokeExact(pattern, patternLen) as MemorySegment?
        } catch (t: Throwable) {
            throw AssertionError("FFM call failed", t)
        }
    }

    fun free(handle: MemorySegment?) {
        try {
            FREE.invokeExact(handle)
        } catch (t: Throwable) {
            throw AssertionError("FFM call failed", t)
        }
    }

    fun ok(handle: MemorySegment?): Boolean {
        try {
            return OK.invokeExact(handle) as Boolean
        } catch (t: Throwable) {
            throw AssertionError("FFM call failed", t)
        }
    }

    fun error(handle: MemorySegment?): String {
        try {
            val errPtr = ERROR.invokeExact(handle) as MemorySegment
            return errPtr.reinterpret(1024).getString(0)
        } catch (t: Throwable) {
            throw AssertionError("FFM call failed", t)
        }
    }

    fun numCapturingGroups(handle: MemorySegment?): Int {
        try {
            return NUM_CAPTURING_GROUPS.invokeExact(handle) as Int
        } catch (t: Throwable) {
            throw AssertionError("FFM call failed", t)
        }
    }

    fun match(
        handle: MemorySegment?,
        text: MemorySegment?,
        textLen: Int,
        regionStart: Int,
        regionEnd: Int,
        startpos: Int,
        anchor: Int,
        matchesOut: MemorySegment?,
        nmatches: Int
    ): Boolean {
        try {
            return MATCH.invokeExact(
                handle,
                text,
                textLen,
                regionStart,
                regionEnd,
                startpos,
                anchor,
                matchesOut,
                nmatches
            ) as Boolean
        } catch (t: Throwable) {
            throw AssertionError("FFM call failed", t)
        }
    }

    fun findAll(
        handle: MemorySegment?,
        text: MemorySegment?,
        textLen: Int,
        matchesOut: MemorySegment?,
        maxMatches: Int
    ): Int {
        try {
            return FIND_ALL.invokeExact(handle, text, textLen, matchesOut, maxMatches) as Int
        } catch (t: Throwable) {
            throw AssertionError("FFM call failed", t)
        }
    }

    fun replaceAll(
        handle: MemorySegment?,
        text: MemorySegment?,
        textLen: Int,
        rewrite: MemorySegment?,
        rewriteLen: Int,
        outBuf: MemorySegment?,
        outCap: Int,
        outLen: MemorySegment?
    ): Int {
        try {
            return REPLACE_ALL.invokeExact(
                handle, text, textLen, rewrite, rewriteLen, outBuf, outCap, outLen
            ) as Int
        } catch (t: Throwable) {
            throw AssertionError("FFM call failed", t)
        }
    }

    fun replaceLiteral(
        input: MemorySegment?,
        inputLen: Int,
        groups: MemorySegment?,
        groupsCount: Int,
        replacement: MemorySegment?,
        replacementLen: Int,
        output: MemorySegment?
    ) {
        try {
            REPLACE_LITERAL.invokeExact(
                input, inputLen, groups, groupsCount, replacement, replacementLen, output
            )
        } catch (t: Throwable) {
            throw AssertionError("FFM call failed", t)
        }
    }

    fun replaceFirst(
        handle: MemorySegment?,
        text: MemorySegment?,
        textLen: Int,
        rewrite: MemorySegment?,
        rewriteLen: Int,
        outBuf: MemorySegment?,
        outCap: Int,
        outLen: MemorySegment?
    ): Int {
        try {
            return REPLACE_FIRST.invokeExact(
                handle, text, textLen, rewrite, rewriteLen, outBuf, outCap, outLen
            ) as Int
        } catch (t: Throwable) {
            throw AssertionError("FFM call failed", t)
        }
    }

    fun replaceAll(
        re2Ptr: MemorySegment?, textBytes: ByteArray, textString: String?, rewriteBytes: ByteArray
    ): ReplaceResult {
        Arena.ofConfined().use { arena ->
            val textSeg: MemorySegment? = arena.allocateFrom(ValueLayout.JAVA_BYTE, textBytes)
            val rewriteSeg: MemorySegment? = arena.allocateFrom(ValueLayout.JAVA_BYTE, rewriteBytes)
            val outLenSeg = arena.allocate(ValueLayout.JAVA_INT)
            val cap = Math.max(textBytes.length * 2, 256)
            var outBuf = arena.allocate(cap.toLong())
            var count =
                replaceAll(
                    re2Ptr,
                    textSeg,
                    textBytes.length,
                    rewriteSeg,
                    rewriteBytes.length,
                    outBuf,
                    cap,
                    outLenSeg
                )
            if (count < 0) {
                val needed = outLenSeg.get(ValueLayout.JAVA_INT, 0)
                outBuf = arena.allocate(needed.toLong())
                count =
                    replaceAll(
                        re2Ptr,
                        textSeg,
                        textBytes.length,
                        rewriteSeg,
                        rewriteBytes.length,
                        outBuf,
                        needed,
                        outLenSeg
                    )
            }
            if (count == 0) {
                return ReplaceResult(textString, 0)
            }
            val actualLen = outLenSeg.get(ValueLayout.JAVA_INT, 0)
            val resBytes = ByteArray(actualLen)
            MemorySegment.copy(outBuf, ValueLayout.JAVA_BYTE, 0, resBytes, 0, actualLen)
            return ReplaceResult(String(resBytes, StandardCharsets.UTF_8), count)
        }
    }

    @JvmRecord
    data class ReplaceResult(val result: String?, val count: Int)
}
