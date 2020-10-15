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

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Public API that the static analysis exposes to applications. It computes and provides access to
 * the following information:
 *
 * A {@link #isReachable(Class) type is reachable} if any field or method of the type is reachable,
 * the type is {@link #isInstantiated(Class) instantiated}, the type is used in a type check or
 * another operation that requires the {@link Class} of the type, or if any subtype is reachable.
 *
 * A {@link #isInstantiated(Class) type is instantiated} if an allocation instruction is present in
 * any {@link #isReachable(Executable) reachable method}, or if an instance of the type is reachable
 * in the image heap. The instantiation status is independent from subtypes, i.e., a type is not
 * marked as instantiated just because a subtype is marked as instantiated. Abstract classes and
 * interfaces are never marked as instantiated.
 *
 * A {@link #isReachable(Field) field is reachable} if a field access (load or store) is present in
 * any {@link #isReachable(Executable) reachable method}.
 *
 * A {@link #isReachable(Executable) method is reachable} if the method body of the method can be
 * executed, i.e., if the method is invoked via a direct call, or the method is invoked via an
 * indirect call for a receiver type that is {@link #isInstantiated(Class) instantiated}. Abstract
 * methods and interface methods, i.e., methods that do not have a method body, are therefore never
 * marked as reachable themselves.
 *
 * A typical client performs these steps:
 * <ol>
 * <li>Configure the analysis by calling methods that are only allowed before the analysis has
 * started, like {@link #registerClassInitializationHandler}, {@link #registerObjectTransformer},
 * {@link #registerFieldValueTransformer}</li><
 * <li>Add root elements to the analysis using {@link #registerAsReachable}. For example, entry
 * points of the application can be registered that way. After adding the first element, the
 * analysis starts running in the background. New root elements can be added at any time later
 * on.</li>
 * <li>Register reachability handler that are notified when a certain element gets reachable, like
 * {@link #registerReachabilityHandler}, {@link #registerMethodOverrideReachabilityHandler},
 * {@link #registerSubtypeReachabilityHandler}. New reachability handler can be added at any time.
 * The reachability handler themselves can also register new root elements or new reachability
 * handler themselves.</li>
 * <li>Invoke {@link #finish()} to wait until the analysis has reached a fixed point.</li>
 * <li>Use the various {@link #isReachable} methods to query analysis results.</li>
 * <li>At this time, it is still valid to {@link #registerAsReachable add new root elements}, but
 * keep in mind that {@link #finish()} needs to be invoked then again too.</li>
 * <li>Use {@link #shutdown()}} to close down the analysis.</li>
 * </ol>
 */
public interface StaticAnalysis {

    /**
     * Registers a predicate that is invoked for a reachable type to decide if that type should be
     * initialized at build time or at run time.
     *
     * The predicate is only invoked for a type if all supertypes that can affect class
     * initialization - the superclass and all superinterfaces that declared default methods - are
     * initialized at build time. If any of these supertypes must be initialized at run time, then
     * implicitly that type must be initialized at run time too.
     *
     * Only one class initialization handler can be registered, and it must be registered before the
     * analysis is started.
     */
    void registerClassInitializationHandler(Predicate<Class<?>> newClassInitializationHandler);

    /**
     * Registers an object transformer that is invoked for objects that are reachable in the image
     * heap. It is invoked before any fields of that object are accessed, i.e., the object
     * transformer can perform eager initialization of fields before the fields are snapshotted for
     * the image heap.
     *
     * If the transformer functions returns a different object than original input, the object is
     * replaced in the image heap, i.e. the original object is not reachable and the returned object
     * is reachable instead. The class of the replacement object must be the same as the class of
     * the original object. All registered object transformers are executed again on the replacement
     * object.
     *
     * Registered object transformers are executed in the order that they are registered. If an
     * object is not an instance of the class provided in the registration, that object transformer
     * is skipped for the object. Each object transformer is invoked exactly once per reachable
     * object.
     *
     * All object transformer must be registered before the analysis is started.
     *
     * @param objectTransformer The new object transformer, to be appended at the end of the list of
     *            registered transformers.
     * @param classes The class of objects handled by the object transformer. This includes all
     *            subclasses of all the provided classes.
     */
    <T> void registerObjectTransformer(Function<T, T> objectTransformer, Class<?>... classes);

    interface FieldValueTransformer {
        Object transform(Field field, Object receiver, Object originalValue);
    }

    /**
     * Registers a field value transformer that is invoked for the value of fields that are
     * reachable in the image heap. Field values are read and transformed after all
     * {@link #registerObjectTransformer object transformer} have been executed. Each field value
     * transformer is invoked exactly once for each reachable field of each reachable object.
     *
     * If the field value replacer returns a different value thanthe original input value, the value
     * is replaced in the image heap, i.e., the original value is not used. The class of the
     * replacement value must be compatible with the {@link Field#getType() type} of the field.
     * Primitive field values are provided as boxed objects, and must be returned as boxed objects.
     *
     * Only one field value replacer can be registered per field.
     *
     * @param fieldValueTransformer The new field value transformer
     * @param fields The fields that the field value transformer is registered for.
     */
    void registerFieldValueTransformer(FieldValueTransformer fieldValueTransformer, Field... fields);

    /**
     * Marks the provided element as reachable. The elements can be of the following types:
     * <ul>
     * <li>{@link Class} to specify reachability of a class
     * <li>{@link Field} to specify reachability of a field
     * <li>{@link Executable} to specify reachability of a method or constructor
     * </ul>
     * The analysis starts running immediately after the elements are added, i.e., registered
     * callbacks can be invoked from other threads even before {@link #finish} is invoked.
     */
    void registerAsReachable(Object... elements);

    /**
     * Performs the fixed-point analysis that finds all methods transitively reachable from the
     * {@link #registerAsReachable root elements}.
     *
     * The method returns when the analysis has reached a fixed point, i.e., the analysis results do
     * not change anymore and no registered callbacks are invoked anymore, unless new
     * {@link #registerAsReachable root elements} are added.
     */
    void finish();

    /**
     * Closes the analysis, i.e., disallows the addition of new {@link #registerAsReachable root
     * elements}.
     */
    void shutdown();

    /**
     * Returns true if the static analysis determined that the provided class is reachable.
     */
    boolean isReachable(Class<?> clazz);

    /**
     * Returns true if the static analysis determined that the provided class is instantiated.
     */
    boolean isInstantiated(Class<?> clazz);

    /**
     * Returns true if the static analysis determined that the provided field is reachable.
     */
    boolean isReachable(Field field);

    /**
     * Returns true if the static analysis determined that the provided method is reachable.
     */
    boolean isReachable(Executable method);

    /**
     * Returns all subtypes of the provided baseClass that the static analysis determined to be
     * reachable time (including the provided baseClass itself).
     */
    Set<Class<?>> getReachableSubtypes(Class<?> baseClass);

    /**
     * Returns all method overrides of the provided baseMethod} that the static analysis determined
     * to be reachable (including the provided baseMethod itself).
     *
     * This method can be called for methods that cannot be overwritten (constructors, static
     * methods, private methods). It returns either an empty result or a 1-element result, based on
     * the {@link #isReachable(Executable) reachability} of the provided baseMethod.
     */
    Set<Executable> getReachableMethodOverrides(Executable baseMethod);

    /**
     * Registers a callback that is invoked once during analysis when any of the provided elements
     * is determined to be reachable at run time. The elements can only be of the following types:
     * <ul>
     * <li>{@link Class} to specify reachability of the given class
     * <li>{@link Field} to specify reachability of a field
     * <li>{@link Executable} to specify reachability of a method or constructor
     * </ul>
     */
    void registerReachabilityHandler(Runnable callback, Object... elements);

    /**
     * Registers a callback that is invoked once each time a subtype of the class specified by the
     * provided baseClass is determined to be reachable. In addition the callback is also invoked
     * once when the provided baseClass itself becomes reachable. The specific class that becomes
     * reachable is passed to the callback as the parameter.
     */
    void registerSubtypeReachabilityHandler(Consumer<Class<?>> callback, Class<?> baseClass);

    /**
     * Registers a callback that is invoked once during analysis each time a method that overrides
     * the provided baseMethod is determined to be reachable. In addition, the callback is also
     * invoked once when the provided baseMethod itself becomes reachable. The specific method that
     * becomes reachable is passed to the callback as the parameter.
     */
    void registerMethodOverrideReachabilityHandler(Consumer<Executable> callback, Executable baseMethod);
}
