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

import java.util.function.Supplier;

import org.graalvm.compiler.nodes.CallTargetNode;
import org.graalvm.compiler.nodes.FrameState;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.graphbuilderconf.ClassInitializationPlugin;
import org.graalvm.compiler.nodes.graphbuilderconf.GraphBuilderContext;

import jdk.vm.ci.hotspot.HotSpotConstantPool;
import jdk.vm.ci.meta.ConstantPool;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;

/**
 * Handles class initialization at run time during bytecode parsing by emitting invokes of the
 * necessary class initializer.
 */
final class ReachabilityAnalysisClassInitializationPlugin implements ClassInitializationPlugin {

    private final ReachabilityAnalysis ra;

    ReachabilityAnalysisClassInitializationPlugin(ReachabilityAnalysis ra) {
        this.ra = ra;
    }

    @Override
    public boolean supportsLazyInitialization(ConstantPool cp) {
        return true;
    }

    @Override
    public void loadReferencedType(GraphBuilderContext builder, ConstantPool constantPool, int cpi, int bytecode) {
        /*
         * The variant of loadReferencedType that does not force class initialization is not
         * available via JVMCI, so we need to cast to the HotSpot implementation type.
         */
        ((HotSpotConstantPool) constantPool).loadReferencedType(cpi, bytecode, false);
    }

    @Override
    public boolean apply(GraphBuilderContext builder, ResolvedJavaType type, Supplier<FrameState> frameState, ValueNode[] classInit) {
        if (builder.getMethod().getDeclaringClass().equals(type) || type.isArray()) {
            return false;
        }

        TypeInfo typeInfo = ra.lookup(type);
        ra.markTypeReachable(type, ra.parsingReason(typeInfo.reachable.get() == null));

        /*
         * Emit a direct invoke of the class initializer for all supertypes that must be
         * transitively initialized: all superclasses and all interfaces that declare default
         * methods.
         */
        for (TypeInfo superInfo : typeInfo.supertypes) {
            if (superInfo == typeInfo || ((!type.isInterface() || type.declaresDefaultMethods()) && (!superInfo.type.isInterface() || superInfo.type.declaresDefaultMethods()))) {
                emitInvokeClassInitializer(builder, superInfo);
            }
        }
        return true;
    }

    private void emitInvokeClassInitializer(GraphBuilderContext builder, TypeInfo typeInfo) {
        if (typeInfo.getOrComputeData().initializeAtRunTime) {
            ResolvedJavaMethod clinit = typeInfo.type.getClassInitializer();
            if (clinit != null) {
                /*
                 * Unconditionally emitting a direct invoke is certainly not something that a
                 * compiler could do, but from the point of view of a flow-insensitive static
                 * analysis it is enough to compute the reachability of class initializer.
                 */
                builder.handleReplacedInvoke(CallTargetNode.InvokeKind.Static, clinit, new ValueNode[0], false);
            }
        }
    }
}
