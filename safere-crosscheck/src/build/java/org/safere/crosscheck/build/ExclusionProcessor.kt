// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.crosscheck.build

import java.io.IOException
import java.util.*
import javax.annotation.processing.AbstractProcessor
import javax.annotation.processing.RoundEnvironment
import javax.annotation.processing.SupportedAnnotationTypes
import javax.lang.model.SourceVersion
import javax.lang.model.element.NestingKind
import javax.lang.model.element.TypeElement
import javax.tools.Diagnostic
import javax.tools.StandardLocation

/** Collects top-level crosscheck exclusions during an explicit processing pass.  */ // Run even when no exclusion annotations remain, so we write an empty manifest.
@SupportedAnnotationTypes("*")
class ExclusionProcessor : AbstractProcessor() {
    private val excluded: MutableSet<String?> = TreeSet<String?>()

    override fun getSupportedSourceVersion(): SourceVersion? {
        return SourceVersion.latestSupported()
    }

    override fun process(annotations: MutableSet<out TypeElement?>?, round: RoundEnvironment): Boolean {
        for (element in round.getRootElements()) {
            if (element !is TypeElement || element.getNestingKind() != NestingKind.TOP_LEVEL) {
                continue
            }
            for (annotation in element.getAnnotationMirrors()) {
                val annotationType = annotation.getAnnotationType().asElement() as TypeElement
                if (annotationType.getQualifiedName().contentEquals("org.safere.DisabledForCrosscheck")) {
                    excluded.add(element.getSimpleName().toString() + ".java")
                }
            }
        }
        if (round.processingOver() && !round.errorRaised()) {
            try {
                processingEnv
                    .getFiler()
                    .createResource(StandardLocation.CLASS_OUTPUT, "", "crosscheck-excludes.txt")
                    .openWriter().use { writer ->
                        for (filename in excluded) {
                            writer.write(filename + "\n")
                        }
                    }
            } catch (exception: IOException) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, exception.toString())
            }
        }
        return false
    }
}
