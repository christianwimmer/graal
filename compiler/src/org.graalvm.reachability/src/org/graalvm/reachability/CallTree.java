/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.graalvm.reachability;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.graalvm.collections.Pair;
import org.graalvm.compiler.graph.NodeSourcePosition;

/**
 * Prints a breath-first call tree, useful for inspecting the static analysis results.
 */
final class CallTree {
    private final ReachabilityAnalysis ra;

    private final Map<MethodInfo, CallTreeNode> mapping = new HashMap<>();

    private int numPrinted;

    CallTree(ReachabilityAnalysis ra) {
        this.ra = ra;
    }

    void doPrintCallTree() {
        ArrayDeque<CallTreeNode> workList = new ArrayDeque<>();

        for (MethodInfo rootMethod : ra.rootMethods) {
            CallTreeNode rootNode = new CallTreeNode(rootMethod);
            mapping.put(rootMethod, rootNode);
            workList.add(rootNode);
        }

        while (!workList.isEmpty()) {
            CallTreeNode callerNode = workList.removeFirst();

            callerNode.methodInfo.staticCallees.entrySet().stream()
                            .sorted(Comparator.comparing(e -> e.getKey().method.format("%H.%n(%p)")))
                            .forEach(e -> addCallee(callerNode, e.getKey(), e.getValue(), workList));

            callerNode.methodInfo.specialCallees.entrySet().stream()
                            .filter(e -> e.getKey().reachable.get() != null)
                            .sorted(Comparator.comparing(e -> e.getKey().method.format("%H.%n(%p)")))
                            .forEach(e -> addCallee(callerNode, e.getKey(), e.getValue(), workList));

            callerNode.methodInfo.indirectCallees.entrySet().stream()
                            .sorted(Comparator.comparing(e -> e.getKey().method.format("%H.%n(%p)")))
                            .forEach(e -> e.getKey().allImplementations.forEach(o -> addCallee(callerNode, o, e.getValue(), workList)));
        }

        ra.rootMethods.forEach(root -> printCallTreeNode(mapping.get(root), "root", ""));
        System.out.println("(total: " + numPrinted + " methods)");
    }

    private void addCallee(CallTreeNode callerNode, MethodInfo callee, NodeSourcePosition invokePosition, ArrayDeque<CallTreeNode> workList) {
        if (!mapping.containsKey(callee)) {
            CallTreeNode calleeNode = new CallTreeNode(callee);
            mapping.put(callee, calleeNode);
            callerNode.callees.add(Pair.create(calleeNode, invokePosition));
            workList.add(calleeNode);
        }
    }

    private void printCallTreeNode(CallTreeNode node, String invokePosition, String indent) {
        System.out.println(indent + node.methodInfo.method.format("%H.%n(%p)") + "  " + invokePosition);
        numPrinted++;

        String indented = indent + "  ";
        node.callees.forEach(pair -> printCallTreeNode(pair.getLeft(), pair.getRight().toString(), indented));
    }
}

final class CallTreeNode {
    final MethodInfo methodInfo;
    final List<Pair<CallTreeNode, NodeSourcePosition>> callees = new ArrayList<>();

    CallTreeNode(MethodInfo methodInfo) {
        this.methodInfo = methodInfo;
    }
}
