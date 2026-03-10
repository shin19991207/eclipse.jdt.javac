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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Kinds.KindName;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.JCDiagnostic;

/**
 * Expands javac's single `compiler.err.does.not.override.abstract` diagnostic into
 * one item per missing abstract method, matching ECJ behavior.
 */
final class DoesNotOverrideAbstractProblemFactory {

	static record ExpandedProblem(String message, String[] arguments) {}

	private final Context context;

	DoesNotOverrideAbstractProblemFactory(Context context) {
		this.context = context;
	}

	List<ExpandedProblem> createExpandedProblems(JCDiagnostic diagnostic) {
		if (diagnostic.getArgs().length < 2
				|| !(diagnostic.getArgs()[0] instanceof ClassSymbol classSymbol)
				|| !(diagnostic.getArgs()[1] instanceof MethodSymbol)) {
			return List.of();
		}
		String[] originalArguments = extractStringArguments(diagnostic);
		List<MethodSymbol> missingMethods = getAllUnimplementedAbstractMethods(classSymbol);
		if (missingMethods.isEmpty()) {
			missingMethods.add((MethodSymbol) diagnostic.getArgs()[1]);
		}
		missingMethods.sort((left, right) -> left.toString().compareTo(right.toString()));
		List<ExpandedProblem> expandedProblems = new ArrayList<>(missingMethods.size());
		for (MethodSymbol missingMethod : missingMethods) {
			String missingMethodString = missingMethod.toString();
			String message = formatMissingInheritedAbstractMethodMessage(diagnostic, missingMethodString);
			String[] arguments = replaceMissingMethodInArguments(diagnostic, originalArguments, missingMethodString);
			expandedProblems.add(new ExpandedProblem(message, arguments));
		}
		return expandedProblems;
	}

	private List<MethodSymbol> getAllUnimplementedAbstractMethods(ClassSymbol impl) {
		Types types = Types.instance(this.context);
		List<MethodSymbol> missingMethods = new ArrayList<>();
		collectUnimplementedAbstractMethods(impl, impl, types, new HashSet<>(), new HashSet<>(), missingMethods);
		return missingMethods;
	}

	private void collectUnimplementedAbstractMethods(ClassSymbol impl, ClassSymbol current, Types types,
			Set<ClassSymbol> visitedTypes, Set<String> visitedMethodSignatures, List<MethodSymbol> missingMethods) {
		if (current == null || !visitedTypes.add(current)) {
			return;
		}
		if (current != impl && (current.flags() & (Flags.ABSTRACT | Flags.INTERFACE)) == 0) {
			return;
		}

		for (Symbol symbol : current.getEnclosedElements()) {
			if (!(symbol instanceof MethodSymbol abstractMethod)) {
				continue;
			}
			if ((abstractMethod.flags() & (Flags.ABSTRACT | Flags.DEFAULT | Flags.PRIVATE)) != Flags.ABSTRACT) {
				continue;
			}
			if (isUnimplementedAbstractMethod(impl, abstractMethod, types)) {
				String signature = methodSignatureKey(abstractMethod, types);
				if (visitedMethodSignatures.add(signature)) {
					missingMethods.add(abstractMethod);
				}
			}
		}

		Type superType = types.supertype(current.type);
		if (superType != null && superType.hasTag(TypeTag.CLASS) && superType.tsym instanceof ClassSymbol superClass) {
			collectUnimplementedAbstractMethods(impl, superClass, types, visitedTypes, visitedMethodSignatures, missingMethods);
		}
		for (com.sun.tools.javac.util.List<Type> interfaces = types.interfaces(current.type);
				interfaces.nonEmpty();
				interfaces = interfaces.tail) {
			if (interfaces.head.tsym instanceof ClassSymbol interfaceSymbol) {
				collectUnimplementedAbstractMethods(impl, interfaceSymbol, types, visitedTypes, visitedMethodSignatures, missingMethods);
			}
		}
	}

	private boolean isUnimplementedAbstractMethod(ClassSymbol impl, MethodSymbol abstractMethod, Types types) {
		MethodSymbol implementation = abstractMethod.implementation(impl, types, true);
		if (implementation == null || implementation == abstractMethod) {
			MethodSymbol defaultProvider = types.interfaceCandidates(impl.type, abstractMethod).head;
			if (defaultProvider != null && defaultProvider.overrides(abstractMethod, impl, types, true)) {
				implementation = defaultProvider;
			}
		}
		return implementation == null || implementation == abstractMethod;
	}

	private String methodSignatureKey(MethodSymbol method, Types types) {
		return method.name + "/" + types.erasure(method.type);
	}

	private String formatMissingInheritedAbstractMethodMessage(JCDiagnostic diagnostic, String newMethod) {
		if (diagnostic.getArgs().length > 2
				&& diagnostic.getArgs()[0] instanceof ClassSymbol currentClass
				&& diagnostic.getArgs()[2] instanceof ClassSymbol abstractOwner) {
			return "The type " + currentClass + " must implement the inherited abstract method " + abstractOwner + "." + newMethod;
		}
		return diagnostic.getMessage(Locale.getDefault());
	}

	private static String[] replaceMissingMethodInArguments(JCDiagnostic diagnostic, String[] arguments, String newMethod) {
		if (arguments.length < 2) {
			return arguments;
		}
		String[] replacement = arguments.clone();
		if (diagnostic.getArgs().length > 2 && diagnostic.getArgs()[2] instanceof ClassSymbol abstractOwner) {
			replacement[1] = abstractOwner + "." + newMethod;
		} else {
			replacement[1] = newMethod;
		}
		return replacement;
	}

	private String[] extractStringArguments(JCDiagnostic diagnostic) {
		return Stream.of(diagnostic.getArgs())
				.filter(Predicate.not(KindName.class::isInstance))
				.filter(arg -> arg != null)
				.map(Object::toString)
				.toArray(String[]::new);
	}
}
