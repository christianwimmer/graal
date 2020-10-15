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

import java.util.Objects;

import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.nodes.ConstantNode;
import org.graalvm.compiler.phases.common.CanonicalizerPhase;

import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.ConstantReflectionProvider;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.MemoryAccessProvider;
import jdk.vm.ci.meta.MethodHandleAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaType;

/**
 * Provider class that is used during {@link ConstantFoldLoadFieldPlugin bytecode parsing} and
 * {@link CanonicalizerPhase canonicalization} to constant fold memory loads. All data accesses are
 * done via the {@link ReachabilityAnalysis#reachableImageHeapObjects image heap}.
 * 
 * {@link ConstantNode Constant nodes} in the Graal IR do not reference {@link ImageHeapObject}, but
 * the original hosted constant implementations. When folding a memory load, the receiver is
 * therefore first {@link #toImageHeapObject converted to} an {@link ImageHeapObject}. The field
 * value read from the field or array element is then not converted, i.e., the raw value of the
 * field is returned and embedded as the result of the constant folding.
 */
final class ImageHeapConstantReflectionProvider implements ConstantReflectionProvider {

    private final ReachabilityAnalysis ra;

    ImageHeapConstantReflectionProvider(ReachabilityAnalysis ra) {
        this.ra = ra;
    }

    ImageHeapObject toImageHeapObject(Constant constant) {
        if (constant instanceof JavaConstant) {
            JavaConstant javaConstant = (JavaConstant) constant;
            if (javaConstant.getJavaKind() == JavaKind.Object && javaConstant.isNonNull()) {
                return ra.toImageHeapObject(javaConstant, ra.parsingReason(!ra.reachableImageHeapObjects.containsKey(constant)));
            }
        }
        return null;
    }

    @Override
    public JavaConstant readFieldValue(ResolvedJavaField field, JavaConstant receiver) {
        ra.markFieldReachable(field, ra.parsingReason(ra.lookup(field).reachable.get() == null));

        if (field.isStatic()) {
            TypeData declaringClassData = ra.lookup(field.getDeclaringClass()).getOrComputeData();
            if (!declaringClassData.initializeAtRunTime) {
                return declaringClassData.rawStaticFieldValues.get(field).getOrCompute();
            }
        } else {
            ImageHeapObject imageHeapObject = toImageHeapObject(receiver);
            if (imageHeapObject instanceof ImageHeapInstance) {
                return ((ImageHeapInstance) imageHeapObject).rawInstanceFieldValues.get(field).getOrCompute();
            }
        }
        return null;
    }

    @Override
    public Integer readArrayLength(JavaConstant array) {
        ImageHeapObject imageHeapObject = toImageHeapObject(array);
        if (imageHeapObject instanceof ImageHeapArray) {
            return ((ImageHeapArray) imageHeapObject).getLength();
        }
        return null;
    }

    @Override
    public JavaConstant readArrayElement(JavaConstant array, int index) {
        ImageHeapObject imageHeapObject = toImageHeapObject(array);
        if (imageHeapObject instanceof ImageHeapArray) {
            ImageHeapArray imageHeapArray = (ImageHeapArray) imageHeapObject;
            if (index >= 0 && index < imageHeapArray.getLength()) {
                return imageHeapArray.rawArrayElementValues[index];
            }
        }
        return null;
    }

    @Override
    public Boolean constantEquals(Constant x, Constant y) {
        if (x == y) {
            return true;
        }
        /*
         * We need to convert both object constants to ImageHeapObject (possibly making objects
         * reachable in the image heap) because object transfomer can map multiple hosted objects to
         * the same ImageHeapObject.
         */
        ImageHeapObject xObject = toImageHeapObject(x);
        ImageHeapObject yObject = toImageHeapObject(y);
        if (xObject != null && yObject != null) {
            return xObject == yObject;
        } else {
            return x.equals(y);
        }
    }

    @Override
    public JavaConstant asJavaClass(ResolvedJavaType type) {
        ra.markTypeReachable(type, ra.parsingReason(ra.lookup(type).reachable.get() == null));
        return Objects.requireNonNull(ra.hostedConstantReflection.asJavaClass(type));
    }

    @Override
    public Constant asObjectHub(ResolvedJavaType type) {
        ra.markTypeReachable(type, ra.parsingReason(ra.lookup(type).reachable.get() == null));
        return Objects.requireNonNull(ra.hostedConstantReflection.asObjectHub(type));
    }

    @Override
    public ResolvedJavaType asJavaType(Constant constant) {
        return ra.hostedConstantReflection.asJavaType(constant);
    }

    @Override
    public JavaConstant boxPrimitive(JavaConstant source) {
        throw GraalError.shouldNotReachHere();
    }

    @Override
    public JavaConstant unboxPrimitive(JavaConstant source) {
        throw GraalError.shouldNotReachHere();
    }

    @Override
    public JavaConstant forString(String value) {
        throw GraalError.shouldNotReachHere();
    }

    @Override
    public MethodHandleAccessProvider getMethodHandleAccess() {
        throw GraalError.shouldNotReachHere();
    }

    @Override
    public MemoryAccessProvider getMemoryAccessProvider() {
        throw GraalError.shouldNotReachHere();
    }
}
