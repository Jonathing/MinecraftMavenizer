/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.util;

import net.minecraftforge.mcmaven.impl.GlobalOptions;
import net.minecraftforge.mcmaven.impl.util.function.CallableBiConsumer;
import net.minecraftforge.mcmaven.impl.util.function.CallableBoolConsumer;
import net.minecraftforge.mcmaven.impl.util.function.CallableConsumer;
import net.minecraftforge.mcmaven.impl.util.function.CallableFunction;
import net.minecraftforge.util.hash.HashStore;
import net.minecraftforge.util.logging.Log;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnknownNullability;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.SequencedCollection;
import java.util.concurrent.Callable;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.StreamSupport;

/**
 * A task represents a work action that generates a file.
 * <p>The {@link Cacheable} implementation has built-in caching using {@link HashStore}, and it is recommended to
 * create tasks this way using {@link #cachingFile(String, SequencedCollection, Callable, Cacheable.CallbackConsumer)}.
 */
@NotNullByDefault
public interface Task {
    /**
     * Executes this task and returns the output file.
     * <p>It is not always guaranteed to run the work required.
     *
     * @return The output file for this task
     */
    File execute();

    /**
     * Whether this task has had its work executed and the output file has been resolved.
     *
     * @return If this task's output has been resolved
     */
    boolean isResolved();

    /**
     * Whether this task needed to do any actual work in order to calculate its output.
     *
     * @return If this task's output is up-to-date
     */
    default boolean isUpToDate() {
        return false;
    }

    /**
     * The unique name for this task.
     *
     * @return The name for this task
     * @implNote The uniqueness of the task name is not enforced, but is required in order to track and debug the
     * execution process.
     */
    String getName();

    static SequencedCollection<Supplier<Task>> deps(Task... tasks) {
        return Arrays.stream(tasks).map(Util::supplyingSelf).toList();
    }

    @SafeVarargs
    static SequencedCollection<? extends Supplier<? extends Task>> deps(Supplier<? extends Task>... tasks) {
        return List.of(tasks);
    }

    static SequencedCollection<Supplier<Task>> deps(Iterable<?> tasks) {
        return StreamSupport.stream(tasks.spliterator(), false).map(Task::cast).toList();
    }

    private static Supplier<Task> cast(Object obj) {
        return () -> {
            var ret = obj instanceof Supplier<?> supplier ? supplier.get() : obj;
            try {
                return (Task) Objects.requireNonNull(ret);
            } catch (ClassCastException | NullPointerException e) {
                throw new IllegalArgumentException("Expected Task or Supplier<Task>, found %s".formatted(ret != null ? ret.getClass() : "null"));
            }
        };
    }

    abstract class Abstract implements Task {
        private final String name;
        private final SequencedCollection<? extends Supplier<? extends Task>> dependencies;
        private @Nullable File result;

        protected Abstract(String name, SequencedCollection<? extends Supplier<? extends Task>> dependencies) {
            this.name = name;
            this.dependencies = dependencies;
        }

        @Override
        public final String getName() {
            return this.name;
        }

        @Override
        public final boolean isResolved() {
            return this.result != null;
        }

        @Override
        public final File execute() {
            // immediately stop if result is already calculated
            if (this.result == null) {
                // run all task dependencies (order enforced by SequencedCollection)
                for (var t : this.dependencies) {
                    var task = t.get();
                    if (task == null) continue; // Some automated task generators may have a null parent, which is fine.

                    try {
                        task.execute();
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to execute task `%s` which is required by task `%s`".formatted(task.getName(), this.getName()), e);
                    }
                }

                Log.info(this.getName());
                var indent = Log.push();
                var start = System.nanoTime();
                try {
                    this.result = this.doWork();

                    var time = Duration.ofNanos(System.nanoTime() - start);
                    Log.debug(String.format("-> took %d:%02d.%03d", time.toMinutesPart(), time.toSecondsPart(), time.toMillisPart()));
                    Log.debug(String.format("-> %s", this.result.getAbsolutePath()));
                } catch (Exception e) {
                    throw new RuntimeException("Failed to execute task `%s`".formatted(this.getName()), e);
                } finally {
                    Log.pop(indent);
                }
            }

            return this.result;
        }

        protected abstract File doWork() throws Exception;

        @Override
        public String toString() {
            return "Task[" + this.getName() + ']';
        }
    }



    /* SIMPLE IMPLEMENTATION */

    static Task simple(String name, Callable<File> callable) {
        return simple(name, List.of(), callable);
    }

    static Task simple(String name, SequencedCollection<? extends Supplier<? extends Task>> dependencies, Callable<File> callable) {
        return new Simple(name, dependencies, callable);
    }

    final class Simple extends Abstract {
        private final Callable<File> callable;

        private Simple(String name, SequencedCollection<? extends Supplier<? extends Task>> dependencies, Callable<File> callable) {
            super(name, dependencies);
            this.callable = callable;
        }

        protected File doWork() throws Exception {
            return this.callable.call();
        }

        @Override
        public String toString() {
            return "Task.Simple[" + this.getName() + ']';
        }
    }


    /* CACHEABLE IMPLEMENTATION */

    /**
     * Creates a simple cacheable task that outputs a single regular file.
     *
     * @param name             The unique name for the task
     * @param output           The output file for the task
     * @param callbackConsumer The callback consumer to use for this task
     * @return A new task
     * @see #cachingFile(String, SequencedCollection, Callable, Cacheable.CallbackConsumer)
     */
    static Task cachingFile(
        String name,
        File output,
        Cacheable.CallbackConsumer callbackConsumer) {
        return cachingFile(name, () -> output, callbackConsumer);
    }

    /**
     * Creates a simple cacheable task that outputs a single regular file.
     *
     * @param name             The unique name for the task
     * @param output           The output file for the task
     * @param callbackConsumer The callback consumer to use for this task
     * @return A new task
     * @see #cachingFile(String, SequencedCollection, Callable, Cacheable.CallbackConsumer)
     */
    static Task cachingFile(
        String name,
        Callable<File> output,
        Cacheable.CallbackConsumer callbackConsumer) {
        return cachingFile(name, List.of(), output, callbackConsumer);
    }

    /**
     * Creates a simple cacheable task that outputs a single regular file.
     *
     * @param name             The unique name for the task
     * @param output           The output file for the task
     * @param dependencies     The dependencies for this task (allowed types are ({@link Supplier} of) {@link Task})
     * @param callbackConsumer The callback consumer to use for this task
     * @return A new task
     */
    static Task cachingFile(
        String name,
        SequencedCollection<? extends Supplier<? extends Task>> dependencies,
        File output,
        Cacheable.CallbackConsumer callbackConsumer) {
        return cachingFile(name, dependencies, () -> output, callbackConsumer);
    }

    /**
     * Creates a simple cacheable task that outputs a single regular file.
     *
     * @param name             The unique name for the task
     * @param output           The output file for the task
     * @param dependencies     The dependencies for this task (allowed types are ({@link Supplier} of) {@link Task})
     * @param callbackConsumer The callback consumer to use for this task
     * @return A new task
     */
    static Task cachingFile(
        String name,
        SequencedCollection<? extends Supplier<? extends Task>> dependencies,
        Callable<File> output,
        Cacheable.CallbackConsumer callbackConsumer) {
        return new Cacheable(name, dependencies, output, callbackConsumer, Cacheable.TaskHashStore::fromFile, Cacheable.TaskHashStore::finishFile);
    }

    /**
     * Creates a simple cacheable task that outputs a directory.
     *
     * @param name             The unique name for the task
     * @param output           The output file for the task
     * @param callbackConsumer The callback consumer to use for this task
     * @return A new task
     * @see #cachingDir(String, SequencedCollection, Callable, Cacheable.CallbackConsumer)
     */
    static Task cachingDir(
        String name,
        File output,
        Cacheable.CallbackConsumer callbackConsumer) {
        return cachingDir(name, () -> output, callbackConsumer);
    }

    /**
     * Creates a simple cacheable task that outputs a directory.
     *
     * @param name             The unique name for the task
     * @param output           The output file for the task
     * @param callbackConsumer The callback consumer to use for this task
     * @return A new task
     * @see #cachingDir(String, SequencedCollection, Callable, Cacheable.CallbackConsumer)
     */
    static Task cachingDir(
        String name,
        Callable<File> output,
        Cacheable.CallbackConsumer callbackConsumer) {
        return cachingDir(name, List.of(), output, callbackConsumer);
    }

    /**
     * Creates a simple cacheable task that outputs a directory.
     *
     * @param name             The unique name for the task
     * @param output           The output file for the task
     * @param dependencies     The dependencies for this task (allowed types are ({@link Supplier} of) {@link Task})
     * @param callbackConsumer The callback consumer to use for this task
     * @return A new task
     */
    static Task cachingDir(
        String name,
        SequencedCollection<? extends Supplier<? extends Task>> dependencies,
        File output,
        Cacheable.CallbackConsumer callbackConsumer) {
        return cachingDir(name, dependencies, () -> output, callbackConsumer);
    }

    /**
     * Creates a simple cacheable task that outputs a directory.
     *
     * @param name             The unique name for the task
     * @param output           The output file for the task
     * @param dependencies     The dependencies for this task (allowed types are ({@link Supplier} of) {@link Task})
     * @param callbackConsumer The callback consumer to use for this task
     * @return A new task
     */
    static Task cachingDir(
        String name,
        SequencedCollection<? extends Supplier<? extends Task>> dependencies,
        Callable<File> output,
        Cacheable.CallbackConsumer callbackConsumer) {
        return new Cacheable(name, dependencies, output, callbackConsumer, Cacheable.TaskHashStore::fromDir, Cacheable.TaskHashStore::finishDir);
    }

    final class Cacheable extends Abstract {
        private final Callable<File> output;
        private final CallbackConsumer callbackConsumer;

        private final CallableFunction<? super File, TaskHashStore> cacheProvider;
        private final CallableBiConsumer<TaskHashStore, ? super File> cacheFinisher;

        private boolean upToDate;

        private Cacheable(String name, SequencedCollection<? extends Supplier<? extends Task>> dependencies, Callable<File> output, CallbackConsumer callbackConsumer, CallableFunction<? super File, TaskHashStore> cacheProvider, CallableBiConsumer<TaskHashStore, ? super File> cacheFinisher) {
            super(name, dependencies);
            this.output = output;
            this.callbackConsumer = callbackConsumer;

            this.cacheProvider = cacheProvider;
            this.cacheFinisher = cacheFinisher;
        }

        @Override
        public boolean isUpToDate() {
            if (!this.isResolved())
                throw new IllegalStateException("Cannot check if a cacheable task is up-to-date before executing it");

            return this.upToDate;
        }

        protected File doWork() throws Exception {
            var output = this.output.call().getAbsoluteFile();
            var cache = this.cacheProvider.accept(output);

            var callback = new CallbackImpl().check(c -> output.exists() && c.isSame());
            this.callbackConsumer.accept(callback, output);

            callback.setup.accept(cache.lock());
            boolean cacheMiss = !callback.check.test(cache);
            if (cacheMiss) {
                GlobalOptions.assertNotCacheOnly();

                callback.run.accept(cache);
                if (cache.isSaved()) {
                    throw new IllegalStateException("Task `%s` attempted to save cache manually, this is handled internally".formatted(this.getName()));
                } else {
                    this.cacheFinisher.accept(cache.unlock(), output);
                    cache.save();
                }
            } else {
                this.upToDate = true;
                Log.debug("Using cached output");
            }

            callback.cleanup.accept(cacheMiss);
            return output;
        }

        @Override
        public String toString() {
            return "Task.Cacheable[" + this.getName() + ']';
        }

        public interface Callback {
            @Contract("_ -> this")
            Callback setup(CallableConsumer<? super HashStore> setup);

            @Contract("_ -> this")
            Callback check(Predicate<? super HashStore> check);

            @Contract("_ -> this")
            Callback checkWith(UnaryOperator<Predicate<? super HashStore>> check);

            @Contract("_ -> this")
            Callback run(CallableConsumer<? super HashStore> run);

            @Contract("_ -> this")
            Callback cleanup(CallableBoolConsumer cleanup);
        }

        private static final class CallbackImpl implements Callback {
            private CallableConsumer<? super HashStore> setup = CallableConsumer.empty();
            private Predicate<? super HashStore> check = truePredicate();
            private CallableConsumer<? super HashStore> run = CallableConsumer.empty();
            private CallableBoolConsumer cleanup = CallableBoolConsumer.empty();

            private static <T> Predicate<T> truePredicate() {
                return t -> true;
            }

            @Override
            public CallbackImpl setup(@UnknownNullability CallableConsumer<? super HashStore> setup) {
                this.setup = setup != null ? setup : CallableConsumer.empty();
                return this;
            }

            @Override
            public CallbackImpl check(@UnknownNullability Predicate<? super HashStore> check) {
                this.check = check != null ? check : truePredicate();
                return this;
            }

            @Override
            public CallbackImpl checkWith(@UnknownNullability UnaryOperator<Predicate<? super HashStore>> check) {
                if (check != null)
                    this.check = check.apply(this.check);
                return this;
            }

            @Override
            public CallbackImpl run(@UnknownNullability CallableConsumer<? super HashStore> run) {
                this.run = run != null ? run : CallableConsumer.empty();
                return this;
            }

            @Override
            public Callback cleanup(@UnknownNullability CallableBoolConsumer cleanup) {
                this.cleanup = cleanup != null ? cleanup : CallableBoolConsumer.empty();
                return this;
            }
        }

        public interface CallbackConsumer {
            void accept(Callback callback, File output) throws Exception;
        }

        private static final class TaskHashStore extends HashStore {
            public static TaskHashStore fromFile(File path) {
                var file = path.getAbsoluteFile();
                var parent = file.getParentFile();
                ensure(parent);

                return (TaskHashStore) new TaskHashStore(file)
                    .load(new File(parent, file.getName() + ".cache"))
                    .add("output", file);
            }

            public static TaskHashStore fromDir(File path) {
                var directory = path.getAbsoluteFile();
                var parent = directory.getParentFile();
                ensure(directory);

                return (TaskHashStore) new TaskHashStore(directory)
                    .load(new File(parent, directory.getName() + ".dir.cache"));
            }

            private static void finishFile(TaskHashStore cache, File output) {
                cache.add("output", output);
            }

            private static void finishDir(TaskHashStore cache, File output) {
                // NO-OP
            }

            private static void ensure(File path) {
                try {
                    Files.createDirectories(path.toPath());
                } catch (IOException e) {
                    Util.sneak(e);
                }
            }

            private boolean locked;

            private TaskHashStore(File root) {
                super(root);
            }

            private TaskHashStore lock() {
                this.locked = true;
                return this;
            }

            private TaskHashStore unlock() {
                this.locked = false;
                return this;
            }

            @Override
            public void save(File file) {
                if (this.locked)
                    throw new UnsupportedOperationException("Cannot manually call HashStore#save when using Task.Cacheable");

                super.save(file);
            }

            @Override
            public void save() {
                if (this.locked)
                    throw new UnsupportedOperationException("Cannot manually call HashStore#save when using Task.Cacheable");

                super.save();
            }
        }
    }
}
