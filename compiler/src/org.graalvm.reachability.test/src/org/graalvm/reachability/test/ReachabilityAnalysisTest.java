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
package org.graalvm.reachability.test;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.graalvm.reachability.ReachabilityAnalysis;
import org.junit.Assert;
import org.junit.Test;

public class ReachabilityAnalysisTest {

    interface I {
        Object initI = 11;
    }

    interface J extends I {
        Object initJ = 22;

        default void defaultMethod() {
        }
    }

    interface K extends J {
        Object initK = 33;
    }

    static class A {
        static Object initA = 44;

        Object foo(Object arg) {
            Data.invoker((Data) arg);
            return arg;
        }
    }

    static class B extends A implements K {
        static Object initB = 55;

        static int staticField = 42;

        @Override
        Object foo(Object arg) {
            if (arg instanceof Data) {
                return ((Data) arg).f;
            } else {
                return super.foo(arg);
            }
        }
    }

    static class Data {
        static void invoker(Data data) {
            data.privateMethod();
        }

        Data() {
        }

        Data(Object f) {
            this.f = f;
        }

        private void privateMethod() {
        }

        Object f;
    }

    static A identity(A a) {
        return a;
    }

    static void test01Root01() {
        A a = identity(new A());
        a.foo(null);
    }

    static void test01Root02() {
        identity(new B());
    }

    static Object test01Root03() {
        return new Data();
    }

    @Test
    public void test01() throws ReflectiveOperationException {
        Method methodAFoo = A.class.getDeclaredMethod("foo", Object.class);
        Method methodBFoo = B.class.getDeclaredMethod("foo", Object.class);
        Method methodDataInvoker = Data.class.getDeclaredMethod("invoker", Data.class);
        Method methodDataPrivateMethod = Data.class.getDeclaredMethod("privateMethod");

        ReachabilityAnalysis ra = new ReachabilityAnalysis();
        ra.registerClassInitializationHandler(clazz -> false);

        AtomicBoolean foundA = new AtomicBoolean();
        ra.registerReachabilityHandler(() -> foundA.set(true), A.class);
        AtomicBoolean foundB = new AtomicBoolean();
        ra.registerReachabilityHandler(() -> foundB.set(true), B.class);

        Set<Class<?>> subtypesA = Collections.newSetFromMap(new ConcurrentHashMap<>());
        ra.registerSubtypeReachabilityHandler(subtype -> subtypesA.add(subtype), A.class);

        Set<Executable> overridesAFoo = Collections.newSetFromMap(new ConcurrentHashMap<>());
        ra.registerMethodOverrideReachabilityHandler(method -> overridesAFoo.add(method), methodAFoo);

        ra.registerAsReachable(ReachabilityAnalysisTest.class.getDeclaredMethod("test01Root01"));
        ra.finish();

        Assert.assertTrue(foundA.get());
        Assert.assertTrue(ra.isReachable(A.class));
        Assert.assertFalse(foundB.get());
        Assert.assertFalse(ra.isReachable(B.class));
        Assert.assertTrue(ra.isReachable(Data.class));
        Assert.assertFalse(ra.isInstantiated(Data.class));

        Assert.assertTrue(ra.isReachable(methodAFoo));
        Assert.assertFalse(ra.isReachable(methodBFoo));
        Assert.assertTrue(ra.isReachable(methodDataInvoker));
        Assert.assertFalse(ra.isReachable(methodDataPrivateMethod));

        assertEquals(subtypesA, A.class);
        assertEquals(ra.getReachableSubtypes(A.class), A.class);
        assertEquals(overridesAFoo, methodAFoo);
        assertEquals(ra.getReachableMethodOverrides(methodAFoo), methodAFoo);

        ra.registerAsReachable(ReachabilityAnalysisTest.class.getDeclaredMethod("test01Root02"));
        ra.finish();

        Assert.assertTrue(foundA.get());
        Assert.assertTrue(ra.isReachable(A.class));
        Assert.assertTrue(foundB.get());
        Assert.assertTrue(ra.isReachable(B.class));
        Assert.assertTrue(ra.isReachable(Data.class));
        Assert.assertFalse(ra.isInstantiated(Data.class));

        Assert.assertTrue(ra.isReachable(methodAFoo));
        Assert.assertTrue(ra.isReachable(methodBFoo));
        Assert.assertTrue(ra.isReachable(methodDataInvoker));
        Assert.assertFalse(ra.isReachable(methodDataPrivateMethod));

        assertEquals(subtypesA, A.class, B.class);
        assertEquals(ra.getReachableSubtypes(A.class), A.class, B.class);
        assertEquals(overridesAFoo, methodAFoo, methodBFoo);
        assertEquals(ra.getReachableMethodOverrides(methodAFoo), methodAFoo, methodBFoo);

        ra.registerAsReachable(ReachabilityAnalysisTest.class.getDeclaredMethod("test01Root03"));
        ra.finish();

        Assert.assertTrue(ra.isInstantiated(Data.class));
        Assert.assertTrue(ra.isReachable(methodDataInvoker));
        Assert.assertTrue(ra.isReachable(methodDataPrivateMethod));
    }

    static void test02Root() {
        System.out.format("Hello %s at %tc!\n", "World", new Date());
    }

    @Test
    public void test02() throws ReflectiveOperationException {
        long start = System.currentTimeMillis();

        /*
         * JDK classes that must be initialized at run time, otherwise the reachability analysis
         * itself gets reachable.
         */
        HashSet<String> runtimeInitClasses = new HashSet<>(Arrays.asList(
                        "java.lang.ApplicationShutdownHooks"));

        ReachabilityAnalysis ra = new ReachabilityAnalysis();
        ra.registerClassInitializationHandler(clazz -> runtimeInitClasses.contains(clazz.getName()));

        ra.registerFieldValueTransformer((f, r, v) -> null, Class.class.getDeclaredField("classValueMap"));
        ra.registerFieldValueTransformer((f, r, v) -> new ForkJoinPool(), ForkJoinPool.class.getDeclaredField("common"));

        ra.registerAsReachable(ReachabilityAnalysisTest.class.getDeclaredMethod("test02Root"));
        ra.finish();

        long end = System.currentTimeMillis();

        System.out.println("== Reachable types:");
        ra.getReachableTypes().stream().map(t -> t.toJavaName(true)).sorted().forEach(s -> System.out.println(s));
        System.out.println("== Instantiated types:");
        ra.getInstantiatedTypes().stream().map(t -> t.toJavaName(true)).sorted().forEach(s -> System.out.println(s));
        System.out.println("== Implementation Invoked methods:");
        ra.getReachableMethods().stream().map(t -> t.format("%H.%n")).sorted().forEach(s -> System.out.println(s));
        System.out.println("== Accessed fields:");
        ra.getReachableFields().stream().map(t -> t.format("%H.%n")).sorted().forEach(s -> System.out.println(s));

        System.out.println("== Call tree:");
        ra.printCallTree();

        System.out.println("(total Implementation Invoked: " + ra.getReachableMethods().size() + " methods)");

        System.out.println("[time: " + (end - start) + "]");
    }

    private static void assertEquals(Set<?> actual, Object... expected) {
        if (actual.size() != expected.length || !actual.containsAll(Arrays.asList(expected))) {
            Assert.fail(actual + " != " + Arrays.asList(expected));
        }
    }

    static int test03Root01() {
        return B.staticField;
    }

    private static boolean test03ClassInitializationHandler(Class<?> clazz) {
        if (clazz == A.class) {
            return true;
        } else if (clazz == B.class) {
            Assert.fail();
        } else if (clazz == I.class) {
            return true;
        } else if (clazz == J.class) {
            return true;
        } else if (clazz == ReachabilityAnalysisTest.class) {
            return false;
        } else if (clazz.getName().startsWith("java.")) {
            return false;
        }

        Assert.fail();
        return false;
    }

    @Test
    public void test03() throws ReflectiveOperationException {
        ReachabilityAnalysis ra = new ReachabilityAnalysis();

        ra.registerClassInitializationHandler(ReachabilityAnalysisTest::test03ClassInitializationHandler);

        ra.registerAsReachable(ReachabilityAnalysisTest.class.getDeclaredMethod("test03Root01"));
        ra.finish();

    }

    static class M1 {
    }

    static class M2 {
    }

    static final Data d1 = new Data(Integer.valueOf(1));
    static final Data d2 = new Data(Long.valueOf(2));
    static final Data replacement = new Data("3");

    static void test04Root01() {
        if (d1 == d2) {
            sink(new M1());
        } else {
            sink(new M2());
        }
        sink(d1.f);
        sink(d2.f);
        sink(d1.f);
        sink(d2.f);
    }

    static void sink(Object o) {
    }

    @Test
    public void test04a() throws ReflectiveOperationException {
        ReachabilityAnalysis ra = new ReachabilityAnalysis();
        ra.registerClassInitializationHandler(c -> false);
        ra.registerAsReachable(ReachabilityAnalysisTest.class.getDeclaredMethod("test04Root01"));
        ra.finish();

        Assert.assertFalse(ra.isReachable(M1.class));
        Assert.assertTrue(ra.isReachable(M2.class));
        Assert.assertTrue(ra.isReachable(Integer.class));
        Assert.assertTrue(ra.isReachable(Long.class));
        Assert.assertFalse(ra.isReachable(String.class));
        Assert.assertFalse(ra.isReachable(Float.class));
        Assert.assertFalse(ra.isReachable(Double.class));
    }

    @Test
    public void test04b() throws ReflectiveOperationException {
        ReachabilityAnalysis ra = new ReachabilityAnalysis();

        AtomicInteger d1Seen = new AtomicInteger();
        AtomicInteger d2Seen = new AtomicInteger();
        AtomicInteger replacementSeen = new AtomicInteger();
        AtomicInteger otherSeen = new AtomicInteger();

        Function<Data, Data> dataTransformer = original -> {
            if (original == d1) {
                d1Seen.incrementAndGet();
                return replacement;
            } else if (original == d2) {
                d2Seen.incrementAndGet();
                return replacement;
            } else if (original == replacement) {
                replacementSeen.incrementAndGet();
                return original;
            } else {
                otherSeen.incrementAndGet();
                return original;
            }
        };
        ra.registerObjectTransformer(dataTransformer, Data.class);

        ra.registerClassInitializationHandler(c -> false);
        ra.registerAsReachable(ReachabilityAnalysisTest.class.getDeclaredMethod("test04Root01"));
        ra.finish();

        Assert.assertEquals(1, d1Seen.get());
        Assert.assertEquals(1, d2Seen.get());
        Assert.assertEquals(1, replacementSeen.get());
        Assert.assertEquals(0, otherSeen.get());

        Assert.assertTrue(ra.isReachable(M1.class));
        Assert.assertFalse(ra.isReachable(M2.class));
        Assert.assertFalse(ra.isReachable(Integer.class));
        Assert.assertFalse(ra.isReachable(Long.class));
        Assert.assertTrue(ra.isReachable(String.class));
        Assert.assertFalse(ra.isReachable(Float.class));
        Assert.assertFalse(ra.isReachable(Double.class));
    }

    @Test
    public void test04c() throws ReflectiveOperationException {
        ReachabilityAnalysis ra = new ReachabilityAnalysis();

        AtomicInteger d1Seen = new AtomicInteger();
        AtomicInteger d2Seen = new AtomicInteger();
        AtomicInteger replacementSeen = new AtomicInteger();
        AtomicInteger otherSeen = new AtomicInteger();

        ReachabilityAnalysis.FieldValueTransformer fTransformer = (field, receiver, originalValue) -> {
            if (receiver == d1) {
                d1Seen.incrementAndGet();
                return Float.valueOf(1);
            } else if (receiver == d2) {
                d2Seen.incrementAndGet();
                return Double.valueOf(3);
            } else if (receiver == replacement) {
                replacementSeen.incrementAndGet();
                return originalValue;
            } else {
                otherSeen.incrementAndGet();
                return originalValue;
            }
        };
        ra.registerFieldValueTransformer(fTransformer, Data.class.getDeclaredField("f"));

        ra.registerClassInitializationHandler(c -> false);
        ra.registerAsReachable(ReachabilityAnalysisTest.class.getDeclaredMethod("test04Root01"));
        ra.finish();

        Assert.assertEquals(1, d1Seen.get());
        Assert.assertEquals(1, d2Seen.get());
        Assert.assertEquals(0, replacementSeen.get());
        Assert.assertEquals(0, otherSeen.get());

        Assert.assertFalse(ra.isReachable(M1.class));
        Assert.assertTrue(ra.isReachable(M2.class));
        Assert.assertFalse(ra.isReachable(Integer.class));
        Assert.assertFalse(ra.isReachable(Long.class));
        Assert.assertFalse(ra.isReachable(String.class));
        Assert.assertTrue(ra.isReachable(Float.class));
        Assert.assertTrue(ra.isReachable(Double.class));
    }
}
