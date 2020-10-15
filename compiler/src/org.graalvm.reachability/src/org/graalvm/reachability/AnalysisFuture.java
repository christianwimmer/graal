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

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

import org.graalvm.compiler.debug.GraalError;

/**
 * Utility class that converts all exception thrown during task execution to internal analysis
 * errors.
 */
final class AnalysisFuture<V> extends FutureTask<V> {

    AnalysisFuture(Callable<V> callable) {
        super(callable);
    }

    @Override
    protected void setException(Throwable t) {
        super.setException(t);
        /*
         * Fail the analysis immediately, so that no exeption gets swallowed when the result of task
         * is never explicitly requested.
         */
        throw GraalError.shouldNotReachHere(t);
    }

    V getOrCompute() {
        try {
            run();
            return get();
        } catch (InterruptedException | ExecutionException ex) {
            throw GraalError.shouldNotReachHere(ex);
        }
    }
}
