/*******************************************************************************
* Copyright (c) 2026 Microsoft Corporation and others.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Eclipse Public License 2.0
* which accompanies this distribution, and is available at
* https://www.eclipse.org/legal/epl-2.0/
*
* SPDX-License-Identifier: EPL-2.0
*******************************************************************************/

package org.eclipse.jdt.internal.javac.problem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.internal.compiler.problem.ProblemSeverities;

import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.JCDiagnostic;

/**
 * Produces zero, one or many {@link JavacProblem} for a single javac diagnostic.
 */
public final class JavacDiagnosticProblemFactory {
	private static final String COMPILER_ERR_DOES_NOT_OVERRIDE_ABSTRACT = "compiler.err.does.not.override.abstract";
	private static final String DOES_NOT_OVERRIDE_ABSTRACT_MESSAGE_FRAGMENT = "does not override abstract method";

	private final JavacDiagnosticProblemConverter converter;
	private final DoesNotOverrideAbstractProblemFactory doesNotOverrideAbstractProblemFactory;

	public JavacDiagnosticProblemFactory(JavacDiagnosticProblemConverter converter, Context context) {
		this.converter = converter;
		this.doesNotOverrideAbstractProblemFactory = new DoesNotOverrideAbstractProblemFactory(context);
	}

	public List<JavacProblem> createJavacProblems(Diagnostic<? extends JavaFileObject> diagnostic) {
		if (diagnostic instanceof JCDiagnostic jcDiagnostic
				&& COMPILER_ERR_DOES_NOT_OVERRIDE_ABSTRACT.equals(jcDiagnostic.getCode())) {
			return createDoesNotOverrideAbstractProblems(jcDiagnostic);
		}
		String englishMessage = diagnostic.getMessage(Locale.ENGLISH);
		if (englishMessage != null && englishMessage.contains(DOES_NOT_OVERRIDE_ABSTRACT_MESSAGE_FRAGMENT)) {
			// Avoid duplicate raw javac marker; this case is emitted as expanded ECJ-style problems.
			return List.of();
		}
		JavacProblem problem = this.converter.createJavacProblem(diagnostic);
		return problem == null ? List.of() : List.of(problem);
	}

	private List<JavacProblem> createDoesNotOverrideAbstractProblems(JCDiagnostic diagnostic) {
		List<DoesNotOverrideAbstractProblemFactory.ExpandedProblem> expandedProblems =
				this.doesNotOverrideAbstractProblemFactory.createExpandedProblems(diagnostic);
		if (expandedProblems.isEmpty()) {
			return List.of();
		}
		int start = toIntOrDefault(diagnostic.getStartPosition(), 0);
		long endPosition = diagnostic.getEndPosition();
		int end = toIntOrDefault(endPosition, start);
		if (end < start) {
			end = start;
		}
		if (end > start) {
			end--;
		}
		int line = toIntOrDefault(diagnostic.getLineNumber(), 0);
		int column = toIntOrDefault(diagnostic.getColumnNumber(), 0);
		JavaFileObject source = diagnostic.getSource();
		char[] fileName = source != null ? source.getName().toCharArray() : new char[0];
		List<JavacProblem> allProblems = new ArrayList<>(expandedProblems.size());
		for (DoesNotOverrideAbstractProblemFactory.ExpandedProblem expandedProblem : expandedProblems) {
			allProblems.add(new JavacProblem(
					fileName,
					expandedProblem.message(),
					diagnostic.getCode(),
					IProblem.AbstractMethodMustBeImplemented,
					expandedProblem.arguments(),
					ProblemSeverities.Error,
					start,
					end,
					line,
					column));
		}
		return allProblems;
	}

	private static int toIntOrDefault(long value, int defaultValue) {
		if (value < 0 || value > Integer.MAX_VALUE) {
			return defaultValue;
		}
		return (int) value;
	}
}
