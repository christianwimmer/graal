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
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.graalvm.collections.Pair;
import org.graalvm.compiler.api.replacements.SnippetReflectionProvider;
import org.graalvm.compiler.api.runtime.GraalJVMCICompiler;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.debug.DebugOptions;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.graph.NodeSourcePosition;
import org.graalvm.compiler.java.GraphBuilderPhase;
import org.graalvm.compiler.nodes.ConstantNode;
import org.graalvm.compiler.nodes.Invoke;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.graphbuilderconf.GraphBuilderConfiguration;
import org.graalvm.compiler.nodes.graphbuilderconf.GraphBuilderConfiguration.BytecodeExceptionMode;
import org.graalvm.compiler.nodes.graphbuilderconf.GraphBuilderConfiguration.Plugins;
import org.graalvm.compiler.nodes.graphbuilderconf.InvocationPlugins;
import org.graalvm.compiler.nodes.java.AccessFieldNode;
import org.graalvm.compiler.nodes.java.InstanceOfNode;
import org.graalvm.compiler.nodes.java.NewArrayNode;
import org.graalvm.compiler.nodes.java.NewInstanceNode;
import org.graalvm.compiler.nodes.java.NewMultiArrayNode;
import org.graalvm.compiler.nodes.spi.CoreProviders;
import org.graalvm.compiler.nodes.spi.CoreProvidersImpl;
import org.graalvm.compiler.options.OptionValues;
import org.graalvm.compiler.phases.OptimisticOptimizations;
import org.graalvm.compiler.phases.common.CanonicalizerPhase;
import org.graalvm.compiler.runtime.RuntimeProvider;

import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.ConstantReflectionProvider;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.runtime.JVMCI;

/**
 * Implements a reachability analysis that determines reachability of types, fields, and methods; as
 * well as reachable objects.
 *
 * The analysis uses fine-grained parallelism via an {@link #executor}. A general pattern is used
 * for all elements, illustrated here using the reachability of fields: All places that make field
 * reachable call {@link #markFieldReachable}. If the field was already marked as reachable
 * beforehand, this is a no-op. For the first (and only first) call for a particular field,
 * {@link #onFieldReachable} is asynchronously called by submitting it to the executor. To make the
 * registration atomic, a {@link FieldInfo#reachable atomic reference} is used: the initial
 * {@code null} value means unreachable, any non-null value means reachable. To aid debugging, the
 * reason (an arbitrary non-null object) why the field is reachable is stored in
 * {@link FieldInfo#reachable}. This enables printing of the dependency chain that made the field
 * reachable in {@link #formatReason}.
 */
public class ReachabilityAnalysis implements StaticAnalysis {

    final MetaAccessProvider metaAccess;
    final SnippetReflectionProvider hostedSnippetReflection;
    final ConstantReflectionProvider hostedConstantReflection;
    final ConstantReflectionProvider imageHeapConstantReflection;
    final CoreProviders providersForParsing;
    final OptionValues options;

    final GraphBuilderConfiguration graphBuilderConfig;

    final ForkJoinPool executor;

    /**
     * Stores analysis metadata for a particular type. Adding a type to this map is side-effect
     * free, in particular it does not influence the reachability of the type.
     */
    final ConcurrentHashMap<ResolvedJavaType, TypeInfo> types = new ConcurrentHashMap<>();
    /**
     * Stores analysis metadata for a particular method. Adding a method to this map is side-effect
     * free, in particular it does not influence the reachability of the method.
     */
    final ConcurrentHashMap<ResolvedJavaMethod, MethodInfo> methods = new ConcurrentHashMap<>();
    /**
     * Stores analysis metadata for a particular field. Adding a field to this map is side-effect
     * free, in particular it does not influence the reachability of the field.
     */
    final ConcurrentHashMap<ResolvedJavaField, FieldInfo> fields = new ConcurrentHashMap<>();

    /**
     * Stores the image heap, i.e., all reachable objects. Adding an object to this map means that
     * the object is reachable. To make the scanning of transitively reachable objects parallel, the
     * value of a map entry is a {@link AnalysisFuture task} that is already scheduled to be
     * eventually executed. The task can also be synchronously executed if the image heap object is
     * immediately required, e.g., for {@link ImageHeapConstantReflectionProvider#toImageHeapObject
     * constant folding} of a field load during parsing.
     */
    final ConcurrentHashMap<JavaConstant, AnalysisFuture<ImageHeapObject>> reachableImageHeapObjects = new ConcurrentHashMap<>();

    /**
     * All methods manually {@link #registerAsReachable marked as reachable}, for call tree printing
     * only.
     */
    final Set<MethodInfo> rootMethods = ConcurrentHashMap.newKeySet();

    /**
     * All registered object transformers. An optimized list of transformers for a particular type
     * is also cached in {@link TypeInfo#objectTransformers}.
     */
    final List<Pair<List<Class<?>>, Function<Object, Object>>> objectTransformers = new CopyOnWriteArrayList<>();

    /** The predicate that determines if classes are initialized at build time or run time. */
    Predicate<Class<?>> classInitializationHandler;

    /**
     * Stores the exception thrown in a parallel worker thread, so that it can be propagated to the
     * caller in {@link #finish}.
     */
    private Throwable executorException;

    public ReachabilityAnalysis() {
        GraalJVMCICompiler compiler = (GraalJVMCICompiler) JVMCI.getRuntime().getCompiler();
        CoreProviders hostedProviders = compiler.getGraalRuntime().getCapability(RuntimeProvider.class).getHostBackend().getProviders();

        metaAccess = hostedProviders.getMetaAccess();
        hostedConstantReflection = hostedProviders.getConstantReflection();
        hostedSnippetReflection = compiler.getGraalRuntime().getCapability(SnippetReflectionProvider.class);

        imageHeapConstantReflection = new ImageHeapConstantReflectionProvider(this);
        providersForParsing = new CoreProvidersImpl(
                        metaAccess,
                        imageHeapConstantReflection,
                        hostedProviders.getConstantFieldProvider(),
                        hostedProviders.getLowerer(),
                        null,
                        hostedProviders.getStampProvider(),
                        null,
                        null,
                        hostedProviders.getMetaAccessExtensionProvider());

        options = new OptionValues(compiler.getGraalRuntime().getCapability(OptionValues.class),
                        DebugOptions.DumpOnError, true);

        executor = new ForkJoinPool(
                        Runtime.getRuntime().availableProcessors(),
                        ForkJoinPool.defaultForkJoinWorkerThreadFactory,
                        (thread, exception) -> executorException = exception,
                        false);

        InvocationPlugins invocationPlugins = new InvocationPlugins();
        registerInvocationPlugins(invocationPlugins);
        Plugins plugins = new Plugins(invocationPlugins);

        plugins.setClassInitializationPlugin(new ReachabilityAnalysisClassInitializationPlugin(this));
        plugins.appendNodePlugin(new ConstantFoldLoadFieldPlugin());

        /*
         * We want all types to be resolved by the graph builder, i.e., we want classes referenced
         * by the bytecodes to be loaded. Since we do not run the code before static analysis, the
         * classes would otherwise be not loaded yet and the bytecode parser would only create a
         * graph.
         */
        graphBuilderConfig = GraphBuilderConfiguration.getDefault(plugins)
                        .withBytecodeExceptionMode(BytecodeExceptionMode.CheckAll)
                        .withNodeSourcePosition(true)
                        .withEagerResolving(true)
                        .withUnresolvedIsError(true);
    }

    public MetaAccessProvider getMetaAccess() {
        return metaAccess;
    }

    protected SnippetReflectionProvider getSnippetReflection() {
        return hostedSnippetReflection;
    }

    /** Hook for subclasses to register additional invocation plugins. */
    protected void registerInvocationPlugins(InvocationPlugins invocationPlugins) {
    }

    @Override
    public void registerClassInitializationHandler(Predicate<Class<?>> newClassInitializationHandler) {
        if (!rootMethods.isEmpty()) {
            throw new IllegalArgumentException("registerClassInitializationHandler must be called before analysis has started");
        }
        if (classInitializationHandler != null) {
            throw new IllegalArgumentException("registerClassInitializationHandler can be called only once");
        }
        classInitializationHandler = newClassInitializationHandler;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> void registerObjectTransformer(Function<T, T> objectTransformer, Class<?>... classes) {
        if (!reachableImageHeapObjects.isEmpty()) {
            throw new IllegalArgumentException("registerObjectTransformer must be called before analysis has started");
        }
        objectTransformers.add(Pair.create(Arrays.asList(classes), (Function<Object, Object>) objectTransformer));
    }

    @Override
    public void registerFieldValueTransformer(FieldValueTransformer fieldValueTransformer, Field... fields) {
        if (!reachableImageHeapObjects.isEmpty()) {
            throw new IllegalArgumentException("registerFieldValueTransformer must be called before analysis has started");
        }
        Objects.requireNonNull(fieldValueTransformer);

        for (Field field : fields) {
            FieldInfo fieldInfo = lookup(metaAccess.lookupJavaField(field));
            if (fieldInfo.fieldValueTransformer != null) {
                throw GraalError.shouldNotReachHere("Can only register one FieldValueTransformer per field");
            }
            fieldInfo.fieldValueTransformer = fieldValueTransformer;
        }
    }

    private static final RootReason MANUALLY_MARKED = new RootReason("marked by registerAsReachable()");

    @Override
    public void registerAsReachable(Object... elements) {
        for (Object element : elements) {
            if (element instanceof Class) {
                markTypeReachable(metaAccess.lookupJavaType((Class<?>) element), MANUALLY_MARKED);
            } else if (element instanceof ResolvedJavaType) {
                markTypeReachable((ResolvedJavaType) element, MANUALLY_MARKED);
            } else if (element instanceof Field) {
                markFieldReachable(metaAccess.lookupJavaField((Field) element), MANUALLY_MARKED);
            } else if (element instanceof ResolvedJavaField) {
                markFieldReachable((ResolvedJavaField) element, MANUALLY_MARKED);
            } else if (element instanceof Executable) {
                rootMethods.add(markMethodReachable(metaAccess.lookupJavaMethod((Executable) element), MANUALLY_MARKED));
            } else if (element instanceof ResolvedJavaMethod) {
                rootMethods.add(markMethodReachable((ResolvedJavaMethod) element, MANUALLY_MARKED));
            } else {
                throw new IllegalArgumentException("Unsupported element type: " + element.getClass().getTypeName());
            }
        }
    }

    @Override
    public void finish() {
        while (!executor.awaitQuiescence(1, TimeUnit.SECONDS)) {
            /* Process tasks. */
        }
        if (executorException != null) {
            throw rethrow(executorException);
        }
    }

    @Override
    public void shutdown() {
        executor.shutdown();
    }

    @Override
    public boolean isReachable(Class<?> clazz) {
        TypeInfo typeInfo = lookup(metaAccess.lookupJavaType(clazz));
        return typeInfo.reachable.get() != null;
    }

    @Override
    public boolean isInstantiated(Class<?> clazz) {
        TypeInfo typeInfo = lookup(metaAccess.lookupJavaType(clazz));
        return typeInfo.instantiated.get() != null;
    }

    @Override
    public boolean isReachable(Field field) {
        FieldInfo fieldInfo = lookup(metaAccess.lookupJavaField(field));
        return fieldInfo.reachable.get() != null;
    }

    @Override
    public boolean isReachable(Executable method) {
        MethodInfo methodInfo = lookup(metaAccess.lookupJavaMethod(method));
        return methodInfo.reachable.get() != null;
    }

    public Set<ResolvedJavaType> getReachableTypes() {
        return types.values().stream()
                        .filter(typeInfo -> typeInfo.reachable.get() != null)
                        .map(typeInfo -> typeInfo.type)
                        .collect(Collectors.toSet());
    }

    public Set<ResolvedJavaType> getInstantiatedTypes() {
        return types.values().stream()
                        .filter(typeInfo -> typeInfo.instantiated.get() != null)
                        .map(typeInfo -> typeInfo.type)
                        .collect(Collectors.toSet());
    }

    public Set<ResolvedJavaField> getReachableFields() {
        return fields.values().stream()
                        .filter(fieldInfo -> fieldInfo.reachable.get() != null)
                        .map(fieldInfo -> fieldInfo.field)
                        .collect(Collectors.toSet());
    }

    public Set<ResolvedJavaMethod> getReachableMethods() {
        return methods.values().stream()
                        .filter(methodInfo -> methodInfo.reachable.get() != null)
                        .map(methodInfo -> methodInfo.method)
                        .collect(Collectors.toSet());
    }

    @Override
    public Set<Class<?>> getReachableSubtypes(Class<?> baseClass) {
        TypeInfo baseClassInfo = lookup(metaAccess.lookupJavaType(baseClass));

        return baseClassInfo.reachableSubtypes.stream()
                        .map(info -> toReflection(info.type))
                        .collect(Collectors.toSet());
    }

    @Override
    public Set<Executable> getReachableMethodOverrides(Executable baseMethod) {
        MethodInfo baseMethodInfo = lookup(metaAccess.lookupJavaMethod(baseMethod));
        TypeInfo declaringClassInfo = lookup(baseMethodInfo.method.getDeclaringClass());

        return declaringClassInfo.reachableSubtypes.stream()
                        .map(subtype -> subtype.type.resolveConcreteMethod(baseMethodInfo.method, declaringClassInfo.type))
                        .map(method -> lookup(method))
                        .filter(methodInfo -> methodInfo.reachable.get() != null)
                        .map(methodInfo -> toReflection(methodInfo.method))
                        .collect(Collectors.toSet());
    }

    @Override
    public void registerReachabilityHandler(Runnable callback, Object... elements) {
        ElementReachableNotification notification = new ElementReachableNotification(callback);

        boolean notifyCallback = false;
        for (Object element : elements) {
            ElementInfo elementInfo;
            if (element instanceof Class) {
                elementInfo = lookup(metaAccess.lookupJavaType(((Class<?>) element)));
            } else if (element instanceof Field) {
                elementInfo = lookup(metaAccess.lookupJavaField(((Field) element)));
            } else if (element instanceof Executable) {
                elementInfo = lookup(metaAccess.lookupJavaMethod(((Executable) element)));
            } else {
                throw new IllegalArgumentException("Unsupported element type: " + element.getClass().getTypeName());
            }
            elementInfo.elementReachableNotifications.add(notification);
            notifyCallback = notifyCallback || elementInfo.reachable.get() != null;
        }

        if (notifyCallback) {
            /*
             * New callbacks can be registered while the analysis is already running. So after
             * registering the callback, immediately invoke it if an element is already reachable.
             */
            notification.notifyCallback(this);
        }
    }

    @Override
    public void registerSubtypeReachabilityHandler(Consumer<Class<?>> callback, Class<?> baseClass) {
        TypeInfo baseClassInfo = lookup(metaAccess.lookupJavaType(baseClass));

        SubtypeReachableNotification notification = new SubtypeReachableNotification(callback);
        baseClassInfo.subtypeReachableNotifications.add(notification);

        /*
         * New callbacks can be registered while the analysis is already running. So after
         * registering the callback, immediately invoke it for all subtypes that are already
         * reachable.
         */
        getReachableSubtypes(baseClass).forEach(subtype -> notification.notifyCallback(this, subtype));
    }

    @Override
    public void registerMethodOverrideReachabilityHandler(Consumer<Executable> callback, Executable baseMethod) {
        MethodInfo baseMethodInfo = lookup(metaAccess.lookupJavaMethod(baseMethod));
        TypeInfo declaringClassInfo = lookup(baseMethodInfo.method.getDeclaringClass());

        MethodOverrideReachableNotification notification = new MethodOverrideReachableNotification(callback, baseMethodInfo);
        declaringClassInfo.methodOverrideReachableNotifications.add(notification);

        /*
         * New callbacks can be registered while the analysis is already running. So after
         * registering the callback, immediately invoke it for all subtypes that are already
         * reachable.
         */
        getReachableMethodOverrides(baseMethod).forEach(method -> notification.notifyCallback(this, method));
    }

    /**
     * Create the {@link TypeInfo} for the provided type, if it does not exist already. Creating the
     * info object is synchronous and atomic, i.e., exactly one info object is created per type.
     */
    TypeInfo lookup(ResolvedJavaType type) {
        TypeInfo existingTypeInfo = types.get(type);
        if (existingTypeInfo != null) {
            return existingTypeInfo;
        }

        /*
         * The lookup of all supertypes and interfaces must happen before attempting to do an atomic
         * insert of this type, otherwise deadlocks can occur when multiple threads race to build
         * the TypeInfo for a type hierarchy. So we cannot use types.computeIfAbsent().
         */
        Set<TypeInfo> allSupertypes = Collections.newSetFromMap(new LinkedHashMap<>());
        if (type.getSuperclass() != null) {
            allSupertypes.addAll(lookup(type.getSuperclass()).supertypes);
        }
        for (ResolvedJavaType interf : type.getInterfaces()) {
            allSupertypes.addAll(lookup(interf).supertypes);
        }

        TypeInfo newTypeInfo = new TypeInfo(this, type, allSupertypes);
        existingTypeInfo = types.putIfAbsent(type, newTypeInfo);
        return existingTypeInfo != null ? existingTypeInfo : newTypeInfo;
    }

    /**
     * Create the {@link MethodInfo} for the provided method, if it does not exist already. Creating
     * the info object is synchronous and atomic, i.e., exactly one info object is created per
     * method.
     */
    MethodInfo lookup(ResolvedJavaMethod method) {
        return methods.computeIfAbsent(method, m -> new MethodInfo(m));
    }

    /**
     * Create the {@link FieldInfo} for the provided field, if it does not exist already. Creating
     * the info object is synchronous and atomic, i.e., exactly one info object is created per
     * field.
     */
    FieldInfo lookup(ResolvedJavaField field) {
        return fields.computeIfAbsent(field, f -> new FieldInfo(f));
    }

    /**
     * Reachability information must be maintained atomically because multiple threads can race to
     * mark the same element. Only one thread succeeds in changing the atomic reference from null to
     * any non-null reason object, and this is the thread that subsequently performs any additional
     * code like making other elements reachable or calling notification callbacks.
     *
     * We use a {@link AtomicReference} instead of {@link AtomicBoolean} because that provides a
     * convenient way to also maintain the reason why a certain element was marked as reachable.
     * Note that the concrete reason value is not stable across multiple analysis runs. So it must
     * not be used by the analysis to make any subsequent decisions. But it is very useful when
     * debugging why a certain element that is supposed to be unreachable is marked as reachable.
     */
    private boolean atomicMark(AtomicReference<Reason> reference, Reason reason) {
        return reference.compareAndSet(null, Objects.requireNonNull(reason));
    }

    /**
     * Marks the provided method as reachable. The definition of reachability is specified in
     * {@link StaticAnalysis} as "the implementation of the method is invoked".
     */
    MethodInfo markMethodReachable(ResolvedJavaMethod method, Reason reason) {
        MethodInfo methodInfo = lookup(method);
        if (atomicMark(methodInfo.reachable, reason)) {
            TypeInfo declaringClassInfo = markTypeReachable(method.getDeclaringClass(), methodInfo);
            /*
             * Should marking a method as reachable also mark the types of the signature and the
             * return type reachable? What about all the types in the exception handler and local
             * variable tables? For now, we try to be minimal and do not register any of that as
             * reachable.
             */

            executor.execute(() -> onMethodReachable(methodInfo));

            methodInfo.elementReachableNotifications.forEach(notification -> notification.notifyCallback(this));
            declaringClassInfo.supertypes.forEach(superType -> notifyMethodOverride(method, superType));
        }
        return methodInfo;
    }

    private void notifyMethodOverride(ResolvedJavaMethod method, TypeInfo typeInfo) {
        for (MethodOverrideReachableNotification notification : typeInfo.methodOverrideReachableNotifications) {
            ResolvedJavaMethod implMethod = method.getDeclaringClass().resolveConcreteMethod(notification.baseMethodInfo.method, method.getDeclaringClass());
            if (method.equals(implMethod)) {
                notification.notifyCallback(this, toReflection(method));
            }
        }
    }

    private void onMethodReachable(MethodInfo methodInfo) {
        if (methodInfo.method.isNative()) {
            return;
        } else if (ignoreReachableMethod(methodInfo.method)) {
            return;
        }

        /*
         * The method body is reachable, so we parse the bytecode using the GraalVM compiler's
         * bytecode parser into high-level Graal IR. All IR nodes that reference a type, field, or
         * method are then processed accordingly.
         */
        StructuredGraph graph = parse(methodInfo.method);
        for (Node n : graph.getNodes()) {
            if (n instanceof NewInstanceNode) {
                NewInstanceNode node = (NewInstanceNode) n;
                markTypeInstantiated(node.instanceClass(), nodeReason(node));

            } else if (n instanceof NewArrayNode) {
                NewArrayNode node = (NewArrayNode) n;
                markTypeInstantiated(node.elementType().getArrayClass(), nodeReason(node));

            } else if (n instanceof NewMultiArrayNode) {
                NewMultiArrayNode node = (NewMultiArrayNode) n;
                ResolvedJavaType type = node.type();
                for (int i = 0; i < node.dimensionCount(); i++) {
                    markTypeInstantiated(type, nodeReason(node));
                    type = type.getComponentType();
                }

            } else if (n instanceof ConstantNode) {
                ConstantNode node = (ConstantNode) n;
                markConstantReachable(node.getValue(), nodeReason(node));

            } else if (n instanceof InstanceOfNode) {
                InstanceOfNode node = (InstanceOfNode) n;
                markTypeReachable(node.type().getType(), nodeReason(node));

            } else if (n instanceof AccessFieldNode) {
                AccessFieldNode node = (AccessFieldNode) n;
                markFieldReachable(node.field(), nodeReason(node));

            } else if (n instanceof Invoke) {
                Invoke invoke = (Invoke) n;
                ResolvedJavaMethod targetMethod = invoke.callTarget().targetMethod();
                switch (invoke.callTarget().invokeKind()) {
                    case Static:
                        /*
                         * Static method calls make the method body of the callee reachable
                         * immediately.
                         */
                        methodInfo.staticCallees.put(markMethodReachable(targetMethod, nodeReason(n)), n.getNodeSourcePosition());
                        break;
                    case Special:
                        /*
                         * A "invokespecial" in the bytecode is a direct method call to an instance
                         * method. We cannot make the method body reachable immediately, because the
                         * receiver type or any of its subtypes must be instantiated for the method
                         * body to become reachable.
                         */
                        methodInfo.specialCallees.put(markMethodSpecialInvoked(targetMethod, nodeReason(n)), n.getNodeSourcePosition());
                        break;
                    case Virtual:
                    case Interface:
                        /*
                         * For indirect method calls, the callee must be resolved for each
                         * instantiated subtype.
                         */
                        methodInfo.indirectCallees.put(markMethodIndirectInvoked(targetMethod, nodeReason(n)), n.getNodeSourcePosition());
                        break;
                }
            }
        }
    }

    /* Hook for subclasses to ignore the method body of a particular method. */
    protected boolean ignoreReachableMethod(ResolvedJavaMethod method) {
        return false;
    }

    private MethodInfo markMethodIndirectInvoked(ResolvedJavaMethod method, Reason reason) {
        MethodInfo methodInfo = lookup(method);
        if (atomicMark(methodInfo.indirectInvoked, reason)) {
            executor.execute(() -> onMethodIndirectInvoked(methodInfo));
        }
        return methodInfo;
    }

    private void onMethodIndirectInvoked(MethodInfo methodInfo) {
        assert !methodInfo.method.canBeStaticallyBound();

        /*
         * First register the method in all tables, so that a concurrent operation that marks a new
         * subtype as instantiated processes this method.
         */
        TypeInfo declaringClassInfo = lookup(methodInfo.method.getDeclaringClass());
        declaringClassInfo.indirectInvokedMethods.add(methodInfo);

        /* Now iterate all instantiated subtypes and resolve the callee for that subtype. */
        declaringClassInfo.instantiatedSubtypes.stream()
                        .map(subTypeInfo -> subTypeInfo.type.resolveConcreteMethod(methodInfo.method, methodInfo.method.getDeclaringClass()))
                        .forEach(implementationMethod -> methodInfo.allImplementations.add(markMethodReachable(implementationMethod, methodInfo.indirectInvoked.get())));
    }

    private MethodInfo markMethodSpecialInvoked(ResolvedJavaMethod method, Reason reason) {
        MethodInfo methodInfo = lookup(method);
        if (atomicMark(methodInfo.specialInvoked, reason)) {
            executor.execute(() -> onMethodSpecialInvoked(methodInfo));
        }
        return methodInfo;
    }

    private void onMethodSpecialInvoked(MethodInfo methodInfo) {
        lookup(methodInfo.method.getDeclaringClass()).specialInvokedMethods.add(methodInfo);

        /*
         * A "invokespecial" is a direct call, so it is not necessary to resolve the method for each
         * instantiated subtype. But there needs to be at least one instantiated subtype for the
         * method to be marked as reachable.
         */
        if (!lookup(methodInfo.method.getDeclaringClass()).instantiatedSubtypes.isEmpty()) {
            methodInfo.allImplementations.add(markMethodReachable(methodInfo.method, methodInfo.specialInvoked.get()));
        }
    }

    TypeInfo markTypeReachable(ResolvedJavaType type, Reason reason) {
        TypeInfo typeInfo = lookup(type);
        if (typeInfo.reachable.get() != null) {
            return typeInfo;
        }

        /*
         * This is a temporary hack to help debugging problems when state leaks from the JDK that is
         * running the analysis into the analysis itself. There is nothing inherently wrong with the
         * reachability analysis analyzing the reachability analysis itself.
         */
        String typeName = type.toJavaName(true);
        if (typeName.startsWith("jdk.vm.ci.hotspot") || typeName.startsWith(getClass().getPackage().getName()) && !typeName.contains("test")) {
            throw GraalError.shouldNotReachHere(formatReason("Reachability analysis itself is seen as reachable: " + typeName, reason));
        }

        /**
         * All supertypes must be marked as reachable before attempting to mark this type as
         * reachable. Otherwise deadlocks can occur when multiple threads race to mark a type
         * hierarchy as reachable.
         */
        typeInfo.supertypes.stream()
                        .filter(superInfo -> superInfo != typeInfo)
                        .forEach(superInfo -> markTypeReachable(superInfo.type, typeInfo));

        if (atomicMark(typeInfo.reachable, reason)) {
            executor.execute(() -> onTypeReachable(typeInfo));
            executor.execute(typeInfo.typeData);

            typeInfo.elementReachableNotifications.forEach(notification -> notification.notifyCallback(this));
            typeInfo.elementReachableNotifications.forEach(notification -> notification.notifyCallback(this));
        }
        return typeInfo;
    }

    private void onTypeReachable(TypeInfo typeInfo) {
        /*
         * Supertypes and implemented interfaces are already marked as reachable, so nothing to do
         * for them.
         */
        if (typeInfo.type.getComponentType() != null) {
            markTypeReachable(typeInfo.type.getComponentType(), typeInfo);
        }
        if (typeInfo.type.getEnclosingType() != null) {
            markTypeReachable(typeInfo.type.getEnclosingType(), typeInfo);
        }

        /*
         * Marking a type as reachable means the java.lang.Class object of that type is reachable in
         * the image heap.
         */
        markConstantReachable(hostedConstantReflection.asJavaClass(typeInfo.type), typeInfo);

        /*
         * Add the type to the subtype-list of all supertypes, and notify any such registered
         * callbacks.
         */
        for (TypeInfo superInfo : typeInfo.supertypes) {
            if (superInfo.reachableSubtypes.add(typeInfo)) {
                superInfo.subtypeReachableNotifications.forEach(notification -> notification.notifyCallback(this, toReflection(typeInfo.type)));
            }
        }
    }

    /*
     * Computes the class initialization status and the snapshot of all static fields. This is an
     * expensive operation and therefore done in an asynchronous task. Use {@link
     * TypeInfo#getOrComputeData} to force execution of the task if the information is required
     * immediately.
     */
    TypeData computeTypeData(TypeInfo typeInfo) {
        GraalError.guarantee(typeInfo.reachable.get() != null, "TypeData is only available for reachable types");

        boolean superInitializedAtRunTime = false;
        for (TypeInfo superType : typeInfo.supertypes) {
            if (superType != typeInfo && (!superType.type.isInterface() || superType.type.declaresDefaultMethods())) {
                /*
                 * This computes the class initialization status for the supertype. As a side
                 * effect, it also runs the class initializer of the supertype if the supertype is
                 * marked for initialization at build time.
                 */
                TypeData superData = superType.getOrComputeData();
                superInitializedAtRunTime |= superData.initializeAtRunTime;
            }
        }

        /* Decide if the type should be initialized at build time or at run time: */
        boolean initializeAtRunTime;
        if (typeInfo.type.isJavaLangObject()) {
            /*
             * java.lang.Object is always initialized at build time. It is the superclass of all
             * array classes too, and we know it does not have a class initializer anyway.
             */
            initializeAtRunTime = false;
        } else if (typeInfo.type.isArray() || typeInfo.type.isPrimitive()) {
            /*
             * Array and primitive types do not have class initializer, so they can always be marked
             * as initialized at build time.
             */
            initializeAtRunTime = false;
        } else if (superInitializedAtRunTime) {
            /*
             * If a supertype is marked for initialization at run time, this type needs to be
             * initialized at run time too because class initialization is transitive.
             */
            initializeAtRunTime = true;
        } else {
            /* No status determined yet for this type, so ask the registered handler. */
            initializeAtRunTime = classInitializationHandler.test(toReflection(typeInfo.type));
        }

        if (!initializeAtRunTime) {
            try {
                /* Run the class initializer. Note that this is running user code. */
                typeInfo.type.initialize();
            } catch (Throwable ex) {
                /*
                 * Any failure to initialize the class just marks the class for initialization at
                 * run time. We could also report that as an error to the user, or print a warning.
                 */
                initializeAtRunTime = true;
            }
        }

        Map<ResolvedJavaField, AnalysisFuture<JavaConstant>> rawStaticFieldValues = null;
        if (!initializeAtRunTime) {
            /*
             * Snapshot all static fields. This reads the raw field value of all fields regardless
             * of reachability status. The field value is processed when a field is marked as
             * reachable, in onFieldReachable().
             */
            rawStaticFieldValues = new HashMap<>();
            for (ResolvedJavaField field : typeInfo.type.getStaticFields()) {
                FieldInfo fieldInfo = lookup(field);
                JavaConstant rawFieldValue = hostedConstantReflection.readFieldValue(field, null);
                rawStaticFieldValues.put(field, new AnalysisFuture<>(() -> onFieldValueReachable(fieldInfo, null, rawFieldValue, fieldInfo)));
            }
        }

        return new TypeData(initializeAtRunTime, rawStaticFieldValues);
    }

    void markTypeInstantiated(ResolvedJavaType type, Reason reason) {
        markTypeReachable(type, reason);

        TypeInfo typeInfo = lookup(type);
        if (atomicMark(typeInfo.instantiated, reason)) {
            executor.execute(() -> onTypeInstantiated(typeInfo));
        }
    }

    private void onTypeInstantiated(TypeInfo typeInfo) {
        if (typeInfo.type.isArray()) {
            /*
             * Array types do not have method overrides, so we do not need them in
             * allInstantiatedSubtypes.
             */
            return;
        }

        /*
         * First add this type to all lists of instantiated subtypes. This ensures that we never
         * loose a type when new methods are marked as indirect / special invoked.
         */
        typeInfo.supertypes.forEach(superInfo -> superInfo.instantiatedSubtypes.add(typeInfo));

        /*
         * Now handle all method overrides for indirect / special invoked methods. This is the
         * inverse of onMethodIndirectInvoked() and onMethodSpecialInvoked().
         */
        typeInfo.supertypes.forEach(superInfo -> {
            for (MethodInfo methodInfo : superInfo.indirectInvokedMethods) {
                assert !methodInfo.method.canBeStaticallyBound();
                ResolvedJavaMethod implementationMethod = typeInfo.type.resolveConcreteMethod(methodInfo.method, methodInfo.method.getDeclaringClass());
                methodInfo.allImplementations.add(markMethodReachable(implementationMethod, methodInfo.indirectInvoked.get()));
            }
            for (MethodInfo methodInfo : superInfo.specialInvokedMethods) {
                methodInfo.allImplementations.add(markMethodReachable(methodInfo.method, methodInfo.specialInvoked.get()));
            }
        });
    }

    void markFieldReachable(ResolvedJavaField field, Reason reason) {
        FieldInfo fieldInfo = lookup(field);
        if (atomicMark(fieldInfo.reachable, reason)) {
            markTypeReachable(field.getDeclaringClass(), fieldInfo);
            /*
             * Should marking a field as reachable also mark the type of the field reachable? So far
             * we have not seen the need for it, so we try to be minimal and do not register it.
             */

            executor.execute(() -> onFieldReachable(fieldInfo));

            fieldInfo.elementReachableNotifications.forEach(notification -> notification.notifyCallback(this));
        }
    }

    private void onFieldReachable(FieldInfo fieldInfo) {
        TypeInfo declaringClassInfo = lookup(fieldInfo.field.getDeclaringClass());

        if (fieldInfo.field.isStatic()) {
            TypeData declaringClassData = declaringClassInfo.getOrComputeData();
            if (!declaringClassData.initializeAtRunTime) {
                executor.execute(declaringClassData.rawStaticFieldValues.get(fieldInfo.field));
            }
        } else {
            for (TypeInfo subtypeInfo : declaringClassInfo.instantiatedSubtypes) {
                for (ImageHeapObject imageHeapObject : subtypeInfo.reachableImageHeapObjects) {
                    executor.execute(((ImageHeapInstance) imageHeapObject).rawInstanceFieldValues.get(fieldInfo.field));
                }
            }
        }
    }

    void markConstantReachable(Constant constant, Reason reason) {
        if (!(constant instanceof JavaConstant)) {
            /*
             * The bytecode parser sometimes embeds low-level VM constants for types into the
             * high-level graph. Since these constants are the result of type lookups, these types
             * are already marked as reachable. Eventually, the bytecode parser should be changed to
             * only use JavaConstant.
             */
            return;
        }

        JavaConstant javaConstant = (JavaConstant) constant;
        if (javaConstant.getJavaKind() == JavaKind.Object && javaConstant.isNonNull()) {
            getOrCreateConstantReachableTask(javaConstant, reason);
        }

    }

    ImageHeapObject toImageHeapObject(JavaConstant javaConstant, Reason reason) {
        assert javaConstant.getJavaKind() == JavaKind.Object && javaConstant.isNonNull();
        return getOrCreateConstantReachableTask(javaConstant, reason).getOrCompute();
    }

    private AnalysisFuture<ImageHeapObject> getOrCreateConstantReachableTask(JavaConstant javaConstant, Reason reason) {
        Reason nonNullReason = Objects.requireNonNull(reason);
        AnalysisFuture<ImageHeapObject> existingTask = reachableImageHeapObjects.get(javaConstant);
        if (existingTask == null) {
            AnalysisFuture<ImageHeapObject> newTask = new AnalysisFuture<>(() -> replaceAndBuildImageHeapObject(javaConstant, nonNullReason));
            existingTask = reachableImageHeapObjects.putIfAbsent(javaConstant, newTask);
            if (existingTask == null) {
                /*
                 * Immediately schedule the new task. There is no need to have not-yet-reachable
                 * ImageHeapObject.
                 */
                executor.execute(newTask);
                return newTask;
            }
        }
        return existingTask;
    }

    private ImageHeapObject replaceAndBuildImageHeapObject(JavaConstant constant, Reason reason) {
        assert constant.getJavaKind() == JavaKind.Object && !constant.isNull();

        Object unwrapped = hostedSnippetReflection.asObject(Object.class, constant);
        if (unwrapped == null) {
            throw GraalError.shouldNotReachHere(formatReason("Could not unwrap constant", reason));
        } else if (unwrapped instanceof ImageHeapObject) {
            throw GraalError.shouldNotReachHere(formatReason("Double wrapping of constant. Most likely, the reachability analysis code itself is seen as reachable.", reason));
        }

        ResolvedJavaType type = metaAccess.lookupJavaType(constant);
        TypeInfo typeInfo = lookup(type);

        /* Run all the object transformer that are able to process the class of the object. */
        for (Function<Object, Object> objectTransformer : typeInfo.objectTransformers) {
            Object replaced = objectTransformer.apply(unwrapped);
            if (replaced != unwrapped) {
                if (replaced == null || replaced.getClass() != unwrapped.getClass()) {
                    throw GraalError.shouldNotReachHere("Object transformer returned object of different class");
                }
                JavaConstant replacedConstant = hostedSnippetReflection.forObject(replaced);

                /*
                 * This ensures that we have a unique ImageHeapObject for the original and replaced
                 * object. As a side effect, this runs all object transformer again on the replaced
                 * constant.
                 */
                return toImageHeapObject(replacedConstant, reason);
            }
        }

        ImageHeapObject newImageHeapObject;
        if (type.isArray()) {
            int length = hostedConstantReflection.readArrayLength(constant);
            JavaConstant[] arrayElements = new JavaConstant[length];
            for (int i = 0; i < length; i++) {
                arrayElements[i] = hostedConstantReflection.readArrayElement(constant, i);
            }
            newImageHeapObject = new ImageHeapArray(type, arrayElements, reason);

        } else {
            Map<ResolvedJavaField, AnalysisFuture<JavaConstant>> instanceFieldValues = new HashMap<>();
            /*
             * We need to have the new ImageHeapInstance early so that we can reference it in the
             * lambda when the field value gets reachable. But it must not be published to any other
             * thread before all instanceFieldValues are filled in.
             */
            newImageHeapObject = new ImageHeapInstance(type, instanceFieldValues, reason);
            for (ResolvedJavaField field : type.getInstanceFields(true)) {
                FieldInfo fieldInfo = lookup(field);
                JavaConstant rawFieldValue = hostedConstantReflection.readFieldValue(field, constant);
                instanceFieldValues.put(field, new AnalysisFuture<>(() -> onFieldValueReachable(fieldInfo, constant, rawFieldValue, newImageHeapObject)));
            }
        }

        /*
         * Following all the array elements and reachable field values can be done asynchronously.
         */
        executor.execute(() ->

        onObjectReachable(newImageHeapObject));
        return newImageHeapObject;
    }

    JavaConstant onFieldValueReachable(FieldInfo fieldInfo, JavaConstant receiver, JavaConstant rawValue, Reason reason) {
        GraalError.guarantee(fieldInfo.reachable.get() != null, "Field value is only reachable when field is reachable");

        JavaConstant transformedValue = transformFieldValue(fieldInfo, receiver, rawValue);
        /* Add the transformed value to the image heap. */
        markConstantReachable(transformedValue, reason);

        /* Return the transformed value, but NOT the image heap object. */
        return transformedValue;
    }

    private JavaConstant transformFieldValue(FieldInfo fieldInfo, JavaConstant receiverConstant, JavaConstant originalValueConstant) {
        if (fieldInfo.fieldValueTransformer == null) {
            return originalValueConstant;
        }

        Field field = toReflection(fieldInfo.field);
        Object receiver = receiverConstant == null ? null : hostedSnippetReflection.asObject(Object.class, receiverConstant);
        Object originalValue = hostedSnippetReflection.asObject(Object.class, originalValueConstant);

        Object newValue = fieldInfo.fieldValueTransformer.transform(field, receiver, originalValue);

        JavaConstant newValueConstant;
        if (newValue == originalValue) {
            newValueConstant = originalValueConstant;
        } else if (fieldInfo.field.getJavaKind().isPrimitive()) {
            newValueConstant = JavaConstant.forBoxedPrimitive(newValue);
            if (newValueConstant == null || newValueConstant.getJavaKind() != fieldInfo.field.getJavaKind()) {
                throw GraalError.shouldNotReachHere("Transformed field value has wrong primitive type");
            }
        } else {
            if (newValue != null && !field.getType().isInstance(newValue)) {
                throw GraalError.shouldNotReachHere("Transformed field value for field " + fieldInfo.field.format("%H.%n") + " is not compatible with type of field: " +
                                "new value " + newValue.getClass().getTypeName() + " is not an instance of " + field.getType());
            }
            newValueConstant = hostedSnippetReflection.forObject(newValue);
        }
        return newValueConstant;
    }

    void onObjectReachable(ImageHeapObject imageHeapObject) {
        lookup(imageHeapObject.type).reachableImageHeapObjects.add(imageHeapObject);

        markTypeInstantiated(imageHeapObject.type, imageHeapObject);

        if (imageHeapObject instanceof ImageHeapArray) {
            ImageHeapArray imageHeapArray = (ImageHeapArray) imageHeapObject;
            for (JavaConstant arrayElement : imageHeapArray.rawArrayElementValues) {
                markConstantReachable(arrayElement, imageHeapArray);
            }

        } else {
            ImageHeapInstance imageHeapInstance = (ImageHeapInstance) imageHeapObject;
            for (ResolvedJavaField field : imageHeapObject.type.getInstanceFields(true)) {
                FieldInfo fieldInfo = lookup(field);
                if (fieldInfo.reachable.get() != null) {
                    executor.execute(imageHeapInstance.rawInstanceFieldValues.get(fieldInfo.field));
                }
            }
        }
    }

    @SuppressWarnings("try")
    private StructuredGraph parse(ResolvedJavaMethod method) {
        /*
         * Build the Graal graph for the method using the bytecode parser of the GraalVM compiler.
         */
        DebugContext debug = new DebugContext.Builder(options).build();
        StructuredGraph graph = new StructuredGraph.Builder(options, debug).method(method).build();
        /*
         * Support for graph dumping, IGV uses this information to show the method name of a graph.
         * Always activating the debug context also allows us to query the graph in parsingReason(),
         * which is useful to track the reason why elements are reachable.
         */
        try (DebugContext.Activation activation = debug.activate(); DebugContext.Scope scope = debug.scope("parse", graph)) {
            /*
             * We do not want Graal to perform any speculative optimistic optimizations, i.e., we do
             * not want to use profiling information. Since we do not run the code before static
             * analysis, the profiling information is empty and therefore wrong.
             */
            OptimisticOptimizations optimisticOpts = OptimisticOptimizations.NONE;

            GraphBuilderPhase.Instance graphBuilder = new GraphBuilderPhase.Instance(providersForParsing, graphBuilderConfig, optimisticOpts, null);
            graphBuilder.apply(graph);

            CanonicalizerPhase.create().apply(graph, providersForParsing);

        } catch (Throwable ex) {
            debug.handle(ex);
        }
        return graph;
    }

    Reason nodeReason(Node node) {
        NodeSourcePosition nodeSourcePosition = node.getNodeSourcePosition();
        if (nodeSourcePosition != null) {
            assert nodeSourcePosition.getRootMethod().equals(((StructuredGraph) node.graph()).method());
            return new NodeSourcePositionReason(nodeSourcePosition);
        } else {
            return lookup(((StructuredGraph) node.graph()).method());
        }
    }

    private static final RootReason OPTIMIZED_OUT_PARSING_REASON = new RootReason("[parsingReason]");

    Reason parsingReason(boolean required) {
        if (!required) {
            /*
             * Performance optimization: do not do the expensive context lookup if it is already
             * known that the reason is not going to be used. But assert that we would have a reason
             * available.
             */
            assert DebugContext.forCurrentThread().contextLookup(StructuredGraph.class) != null;
            return OPTIMIZED_OUT_PARSING_REASON;
        }
        StructuredGraph graph = DebugContext.forCurrentThread().contextLookup(StructuredGraph.class);
        return lookup(graph.method());
    }

    @SuppressWarnings({"unchecked"})
    static <E extends Throwable> RuntimeException rethrow(Throwable ex) throws E {
        throw (E) ex;
    }

    private Class<?> toReflection(ResolvedJavaType type) {
        return hostedSnippetReflection.originalClass(type);
    }

    /**
     * The {@link SnippetReflectionProvider} does not (yet) provide a mapping to reflection objects
     * for fields and methods. But the HotSpot JVMCI implementation has an internal method for it
     * that we can invoke reflectively.
     */

    private static final Method hsGetField = findMethod("jdk.vm.ci.hotspot.HotSpotJDKReflection", "getField");
    private static final Method hsGetMethod = findMethod("jdk.vm.ci.hotspot.HotSpotJDKReflection", "getMethod");

    private static Method findMethod(String className, String methodName) {
        try {
            Method result = Arrays.stream(Class.forName(className).getDeclaredMethods())
                            .filter(method -> method.getName().equals(methodName))
                            .reduce((a, b) -> {
                                throw GraalError.shouldNotReachHere(String.format("Method name not unique: " + a + ", " + b));
                            }).get();
            result.setAccessible(true);
            return result;
        } catch (ReflectiveOperationException ex) {
            throw GraalError.shouldNotReachHere(ex);
        }
    }

    protected Field toReflection(ResolvedJavaField field) {
        try {
            return (Field) hsGetField.invoke(null, field);
        } catch (ReflectiveOperationException ex) {
            throw GraalError.shouldNotReachHere(ex);
        }
    }

    protected Executable toReflection(ResolvedJavaMethod method) {
        if (method.isClassInitializer()) {
            throw GraalError.shouldNotReachHere("Class initializer cannot be convert to a java.lang.reflect.Method");
        }
        try {
            return (Executable) hsGetMethod.invoke(null, method);
        } catch (ReflectiveOperationException ex) {
            throw GraalError.shouldNotReachHere(ex);
        }
    }

    protected String formatReason(String message, Reason reason) {
        StringBuilder result = new StringBuilder(message);
        Reason cur = reason;
        while (cur != null) {
            result.append(System.lineSeparator());
            if (cur instanceof NodeSourcePositionReason) {
                NodeSourcePosition nodeSourcePosition = ((NodeSourcePositionReason) cur).nodeSourcePosition;
                result.append("\t\t" + nodeSourcePosition.toString());
                cur = lookup(nodeSourcePosition.getMethod()).reachable.get();
            } else if (cur instanceof MethodInfo) {
                MethodInfo methodInfo = (MethodInfo) cur;
                result.append("\t\tat method " + methodInfo.method.format("%H.%n(%p)"));
                cur = methodInfo.reachable.get();
            } else if (cur instanceof FieldInfo) {
                FieldInfo fieldInfo = (FieldInfo) cur;
                result.append("\t\treachable from field " + fieldInfo.field.format("%H.%n"));
                cur = fieldInfo.reachable.get();
            } else if (cur instanceof TypeInfo) {
                TypeInfo typeInfo = (TypeInfo) cur;
                result.append("\t\treachable from type " + typeInfo.type.toJavaName());
                cur = typeInfo.reachable.get();
            } else if (cur instanceof ImageHeapObject) {
                ImageHeapObject imageHeapObject = (ImageHeapObject) cur;
                result.append("\t\tat object " + imageHeapObject.type.toJavaName(true));
                cur = imageHeapObject.reason;
            } else if (cur instanceof RootReason) {
                String information = ((RootReason) cur).information;
                result.append("\t\troot reason: " + information);
                cur = null;
            } else {
                result.append("\t\tunsupported reason: " + cur.getClass().getTypeName());
                cur = null;
            }
        }
        return result.toString();
    }

    public void printCallTree() {
        new CallTree(this).doPrintCallTree();
    }
}

/**
 * Marker interface to track why a certain element is reachable, instantiated, invoked, ...
 */
interface Reason {
}

class RootReason implements Reason {
    final String information;

    RootReason(String information) {
        this.information = information;
    }
}

class NodeSourcePositionReason implements Reason {
    NodeSourcePosition nodeSourcePosition;

    NodeSourcePositionReason(NodeSourcePosition nodeSourcePosition) {
        this.nodeSourcePosition = nodeSourcePosition;
    }
}

abstract class ElementInfo implements Reason {
    /**
     * Tracks if the element is marked as reachable. The initial value of {@code null} means not
     * reachable, any non-null value means reachable. The value stores the reason, i.e., a reference
     * to any other object that provides debugging information why the element was marked as
     * reachable.
     */
    final AtomicReference<Reason> reachable = new AtomicReference<>();

    /*
     * Contains all registered reachability handler that are notified when the element is marked as
     * reachable.
     */
    final Set<ElementReachableNotification> elementReachableNotifications = ConcurrentHashMap.newKeySet();
}

final class TypeInfo extends ElementInfo {
    final ResolvedJavaType type;

    /**
     * Tracks if the type is marked as instantiated. Differentiating between {@link #reachable} and
     * {@link #instantiated} is important for analysis precision, because overriden methods only
     * need to be marked as reachable for subtypes that are instantiated. Any non-null value means
     * "yes" and denotes the reason, like {@link ElementInfo#reachable}.
     */
    final AtomicReference<Reason> instantiated = new AtomicReference<>();
    /**
     * Additional information that is only available for types that are marked as reachable.
     */
    final AnalysisFuture<TypeData> typeData;
    /**
     * The sub-list of {@link ReachabilityAnalysis#objectTransformers} that apply for this type.
     */
    final List<Function<Object, Object>> objectTransformers;

    /**
     * Contains all supertypes, regardless of reachability status, including this type itself.
     * Transitive superclasses and superinterfaces are included. This set is immutable.
     */
    final Set<TypeInfo> supertypes;
    /**
     * Contains all reachable subtypes, including this type itself if it is reachable.
     */
    final Set<TypeInfo> reachableSubtypes = ConcurrentHashMap.newKeySet();
    /**
     * Contains all instantiated subtypes, including this type itself if it is instantiated.
     */
    final Set<TypeInfo> instantiatedSubtypes = ConcurrentHashMap.newKeySet();
    /**
     * Contains all methods that are declared in this type and that are invoked using an
     * virtual/interface invocation. For each instantiated subtype, the resolved method of that
     * subtype is reachable.
     */
    final Set<MethodInfo> indirectInvokedMethods = ConcurrentHashMap.newKeySet();
    /**
     * Contains all methods that are declared in this type and that are invoked using an
     * invokespecial invocation. If any instantiated subtype is present, the methods are reachable.
     */
    final Set<MethodInfo> specialInvokedMethods = ConcurrentHashMap.newKeySet();
    /**
     * Contains all image heap objects of this type, i.e., a subset of
     * {@link ReachabilityAnalysis#reachableImageHeapObjects}.
     */
    final Set<ImageHeapObject> reachableImageHeapObjects = ConcurrentHashMap.newKeySet();

    final Set<MethodOverrideReachableNotification> methodOverrideReachableNotifications = ConcurrentHashMap.newKeySet();
    final Set<SubtypeReachableNotification> subtypeReachableNotifications = ConcurrentHashMap.newKeySet();

    TypeInfo(ReachabilityAnalysis ra, ResolvedJavaType type, Set<TypeInfo> supertypes) {
        this.type = type;
        supertypes.add(this);
        this.supertypes = Collections.unmodifiableSet(supertypes);
        this.typeData = new AnalysisFuture<>(() -> ra.computeTypeData(this));

        /*
         * Filter out the object transformer that are applicable for this type, to avoid iterating
         * the whole global list for each image heap object.
         */
        this.objectTransformers = ra.objectTransformers.stream()
                        .filter(pair -> pair.getLeft().stream()
                                        .map(clazz -> ra.metaAccess.lookupJavaType(clazz).isAssignableFrom(type))
                                        .reduce(Boolean.FALSE, Boolean::logicalOr))
                        .map(pair -> pair.getRight())
                        .collect(Collectors.toUnmodifiableList());
    }

    TypeData getOrComputeData() {
        GraalError.guarantee(reachable.get() != null, "TypeData is only available for reachable types");
        return this.typeData.getOrCompute();
    }

    @Override
    public String toString() {
        return "TypeInfo{" + type.toJavaName(true) + '}';
    }
}

/**
 * Additional data for a {@link TypeInfo type} that is only available for types that are marked as
 * reachable. Computed lazily once the type is seen as reachable.
 */
final class TypeData {
    /** The class initialization state: initialize the class at build time or at run time. */
    final boolean initializeAtRunTime;
    /**
     * The raw values of all static fields, regardless of field reachability status. Evaluating the
     * {@link AnalysisFuture} runs the {@link StaticAnalysis.FieldValueTransformer} and
     * {@link ReachabilityAnalysis#onFieldValueReachable adds the result to the image heap}.
     */
    final Map<ResolvedJavaField, AnalysisFuture<JavaConstant>> rawStaticFieldValues;

    TypeData(boolean initializeAtRunTime, Map<ResolvedJavaField, AnalysisFuture<JavaConstant>> rawStaticFieldValues) {
        this.initializeAtRunTime = initializeAtRunTime;
        this.rawStaticFieldValues = rawStaticFieldValues;
    }
}

final class MethodInfo extends ElementInfo {
    final ResolvedJavaMethod method;

    /**
     * Stores a reference to a non-null value if the method has been invoked using an invokespecial
     * instruction. Any non-null value means "yes" and denotes the reason, like
     * {@link ElementInfo#reachable}.
     */
    final AtomicReference<Reason> specialInvoked = new AtomicReference<>();
    /**
     * Stores ar reference to a non-null value if the method has been invoked using a
     * virtual/interface call. Any non-null value means "yes" and denotes the reason, like
     * {@link ElementInfo#reachable}.
     */
    final AtomicReference<Reason> indirectInvoked = new AtomicReference<>();

    /** Stores all reachable methods that override this method. */
    final Set<MethodInfo> allImplementations = ConcurrentHashMap.newKeySet();

    /* For call tree printing. */
    final Map<MethodInfo, NodeSourcePosition> staticCallees = new ConcurrentHashMap<>();
    final Map<MethodInfo, NodeSourcePosition> specialCallees = new ConcurrentHashMap<>();
    final Map<MethodInfo, NodeSourcePosition> indirectCallees = new ConcurrentHashMap<>();

    MethodInfo(ResolvedJavaMethod method) {
        this.method = method;
    }

    @Override
    public String toString() {
        return "MethodInfo{" + method.format("%f %H.%n(%p)%r") + '}';
    }
}

final class FieldInfo extends ElementInfo {
    final ResolvedJavaField field;

    /**
     * The {@link ReachabilityAnalysis#registerFieldValueTransformer field value transformer
     * registered} for this field.
     */
    ReachabilityAnalysis.FieldValueTransformer fieldValueTransformer;

    FieldInfo(ResolvedJavaField field) {
        this.field = field;
    }

    @Override
    public String toString() {
        return "FieldInfo{" + field.format("%f %H.%n") + '}';
    }
}

abstract class ImageHeapObject implements Reason {
    final ResolvedJavaType type;
    final Reason reason;

    ImageHeapObject(ResolvedJavaType type, Reason reason) {
        this.type = type;
        this.reason = Objects.requireNonNull(reason);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" + type.toJavaName(true) + '}';
    }
}

final class ImageHeapInstance extends ImageHeapObject {
    /**
     * The raw values of all static fields, regardless of field reachability status. Evaluating the
     * {@link AnalysisFuture} runs the {@link StaticAnalysis.FieldValueTransformer} and
     * {@link ReachabilityAnalysis#onFieldValueReachable adds the result to the image heap}.
     */
    final Map<ResolvedJavaField, AnalysisFuture<JavaConstant>> rawInstanceFieldValues;

    ImageHeapInstance(ResolvedJavaType type, Map<ResolvedJavaField, AnalysisFuture<JavaConstant>> rawInstanceFieldValues, Reason reason) {
        super(type, reason);
        this.rawInstanceFieldValues = rawInstanceFieldValues;
    }
}

final class ImageHeapArray extends ImageHeapObject {
    /**
     * The raw values of all array elements. No individual reachability status is maintained for
     * individual array elements, i.e., all array elements are always considered reachable and are
     * added to the image heap as soon as the array {@link ReachabilityAnalysis#onObjectReachable is
     * processed}.
     */
    final JavaConstant[] rawArrayElementValues;

    ImageHeapArray(ResolvedJavaType type, JavaConstant[] rawArrayElementValues, Reason reason) {
        super(type, reason);
        this.rawArrayElementValues = rawArrayElementValues;
    }

    int getLength() {
        return rawArrayElementValues.length;
    }
}

final class ElementReachableNotification {
    final Runnable callback;
    final AtomicBoolean notified = new AtomicBoolean();

    ElementReachableNotification(Runnable callback) {
        this.callback = callback;
    }

    /** Notify the callback exactly once. */
    void notifyCallback(ReachabilityAnalysis ra) {
        if (!notified.getAndSet(true)) {
            ra.executor.execute(() -> callback.run());
        }
    }
}

final class MethodOverrideReachableNotification {
    final Consumer<Executable> callback;
    final MethodInfo baseMethodInfo;
    final Set<Executable> notifiedMethods = ConcurrentHashMap.newKeySet();

    MethodOverrideReachableNotification(Consumer<Executable> callback, MethodInfo baseMethodInfo) {
        this.callback = callback;
        this.baseMethodInfo = baseMethodInfo;
    }

    /** Notify the callback exactly once per method override. */
    void notifyCallback(ReachabilityAnalysis ra, Executable method) {
        if (notifiedMethods.add(method)) {
            ra.executor.execute(() -> callback.accept(method));
        }
    }
}

final class SubtypeReachableNotification {
    final Consumer<Class<?>> callback;
    final Set<Class<?>> notifiedClasses = ConcurrentHashMap.newKeySet();

    SubtypeReachableNotification(Consumer<Class<?>> callback) {
        this.callback = callback;
    }

    /** Notify the callback exactly once per subtype. */
    void notifyCallback(ReachabilityAnalysis ra, Class<?> clazz) {
        if (notifiedClasses.add(clazz)) {
            ra.executor.execute(() -> callback.accept(clazz));
        }
    }
}
