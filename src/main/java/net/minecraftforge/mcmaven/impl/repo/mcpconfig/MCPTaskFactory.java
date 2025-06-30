/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.repo.mcpconfig;

import com.google.gson.reflect.TypeToken;
import io.codechicken.diffpatch.cli.PatchOperation;
import io.codechicken.diffpatch.util.Input.MultiInput;
import io.codechicken.diffpatch.util.LogLevel;
import io.codechicken.diffpatch.util.Output.MultiOutput;
import io.codechicken.diffpatch.util.PatchMode;
import io.codechicken.diffpatch.util.archiver.ArchiveFormat;
import net.minecraftforge.mcmaven.impl.cache.MavenCache;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.mcmaven.impl.util.Constants;
import net.minecraftforge.mcmaven.impl.util.ProcessUtils;
import net.minecraftforge.mcmaven.impl.util.Task;
import net.minecraftforge.mcmaven.impl.util.Util;
import net.minecraftforge.srgutils.IMappingFile;
import net.minecraftforge.util.data.OS;
import net.minecraftforge.util.data.json.JsonData;
import net.minecraftforge.util.data.json.MCPConfig;
import net.minecraftforge.util.file.FileUtils;
import net.minecraftforge.util.hash.HashStore;
import net.minecraftforge.util.logging.Log;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedSet;
import java.util.TreeSet;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

// TODO [MCMavenizer][Documentation] Document
public class MCPTaskFactory {
    private final MCPConfig.V2 cfg;
    private final MCPSide side;
    private final File build;
    private final @Nullable MCPTaskFactory parent;
    private final List<Map<String, String>> steps;
    private final Map<String, Task> data = new HashMap<>();
    private final Map<String, Task> tasks = new LinkedHashMap<>();
    private final Task preStrip;
    private final Task rawJar;
    private final Task mappings;
    private final Task srgJar;
    private final Task preDecomp;
    private final Task last;

    private final BiPredicate<File, String> injectFileFilter;
    private @Nullable List<Lib> libraries = null;

    private MCPTaskFactory(MCPTaskFactory parent, File build, Task preDecomp) {
        this.parent = parent;
        this.build = build;
        this.side = parent.side;
        this.cfg = parent.cfg;
        this.steps = parent.steps;
        this.preStrip = parent.preStrip;
        this.rawJar = parent.rawJar;
        this.mappings = parent.mappings;
        this.srgJar = parent.srgJar;
        this.preDecomp = preDecomp;

        var foundDecomp = false;
        Task last = null;
        for (var step : this.steps) {
            var type = step.get("type");
            var name = step.getOrDefault("name", type);

            Task task;
            if ("decompile".equals(name)) {
                foundDecomp = true;

                // Replace decompile input with our modified task
                var inputTask = step.get("input");
                inputTask = inputTask.substring(1, inputTask.length() - 7);
                tasks.replace(inputTask, preDecomp);

                task = createTask(step);
                tasks.put(name, task);
            } else if (!foundDecomp) {
                task = this.parent.findStep(name);
                tasks.put(name, task);
            } else {
                task = createTask(step);
                tasks.put(name, task);
            }

            last = task;
        }
        this.last = last;

        this.injectFileFilter = this.getFileFilter();
    }

    public MCPTaskFactory(MCPSide side, File build) {
        this.parent = null;
        this.side = side;
        this.build = build;
        this.cfg = this.side.getMCP().getConfig();

        var entries = cfg.getData(this.side.getName());
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            var key = entry.getKey();
            var value = entry.getValue();
            data.put(key, extract(key, value));
        }

        this.steps = cfg.getSteps(this.side.getName());
        if (this.steps.isEmpty())
            throw except("Does not contain requested side `" + side.getName() + "`");

        Task prestrip = null, rawJar = null, mappings = null, srgJar = null, predecomp = null, last = null;
        for (var step : this.steps) {
            var type = step.get("type");
            var name = step.getOrDefault("name", type);

            var task = createTask(step);
            tasks.put(name, task);
            last = task;

            switch (name) {
                case "strip", "stripClient":
                    prestrip = this.findStep(step.get("input"));
                case "merge":
                    rawJar = this.findStep(name);
                    break;
                case "decompile":
                    predecomp = this.findStep(step.get("input"));
                    break;
                case "rename":
                    srgJar = this.findStep(name);

                    var value = step.getOrDefault("mappings", "{mappings}");
                    if (!value.startsWith("{") || !value.endsWith("}"))
                        throw except("Expected `rename` step's `mappings` argument to be a variable");

                    if (value.endsWith("Output}"))
                        mappings = this.findStep(value);
                    else
                        mappings = this.findData(value);
                default:
                    break;
            }
        }

        if (prestrip == null)
            throw except("Could not find `strip%s` step".formatted(MCPSide.JOINED.equals(side.getName()) ? "Client" : ""));

        if (rawJar == null)
            throw except("Could not find `%s` task".formatted(MCPSide.JOINED.equals(side.getName()) ? "merge" : "strip"));

        if (mappings == null)
            throw except("Could not find `mappings` task");

        if (srgJar == null)
            throw except("Could not find `rename` task");

        if (predecomp == null)
            throw except("Could not find `decompile` step");

        if (last == null)
            throw except("No steps defined");

        this.preStrip = prestrip;
        this.rawJar = rawJar;
        this.mappings = mappings;
        this.srgJar = srgJar;
        this.preDecomp = predecomp;
        this.last = last;
        //this.mappingHelper = new MappingTasks(mappings);

        this.injectFileFilter = this.getFileFilter();
    }

    private static final BiPredicate<File, String> TRUE = (f, s) -> true;
    private static final BiPredicate<File, String> NOT_CONTAINS_CLIENT = (f, s) -> !s.contains("client");

    private BiPredicate<File, String> getFileFilter() {
        return this.side.containsClient() ? TRUE : NOT_CONTAINS_CLIENT;
    }

    public MCPTaskFactory child(File dir, Task predecomp) {
        return new MCPTaskFactory(this, dir, predecomp);
    }

    public Task getRawJar() {
        return this.rawJar;
    }

    public Task getMappings() {
        return this.mappings;
    }

    public Task getSrgJar() {
        return this.srgJar;
    }

    public Task getPreDecompile() {
        return this.preDecomp;
    }

    public Task getLastTask() {
        return this.last;
    }

    private RuntimeException except(String message) {
        return new IllegalArgumentException("Invalid MCP Dependency: " + this.side.getMCP().getName() + " - " + message);
    }

    private File getData() {
        return this.side.getMCP().getData();
    }

    private Task findStep(String name) {
        if (name.startsWith("{") && name.endsWith("Output}"))
            name = name.substring(1, name.length() - 7);

        var ret = this.tasks.get(name);
        if (ret == null)
            throw except("Unknown task `" + name + "`");

        return ret;
    }

    public Task findData(String name) {
        if (this.parent != null)
            return this.parent.findData(name);

        if (name.startsWith("{") && name.endsWith("}"))
            name = name.substring(1, name.length() - 1);

        var ret = this.data.get(name);
        if (ret == null)
            throw except("Unknown data entry `" + name + "`");

        return ret;
    }

    private Task extract(String key, String value) {
        if (value.endsWith("/"))
            return extractFolder(key, value);
        return extractSingle(key, value);
    }

    private Task extractSingle(String key, String value) {
        return Task.cachingFile("extract[%s]".formatted(key),
            () -> {
                var idx = value.lastIndexOf('/');
                var filename = idx == -1 ? value : value.substring(idx);
                return new File(this.build, "data/" + key + '/' + filename);
            },
            (callback, target) -> {
                callback.setup(cache -> cache.add("mcp", getData()));

                callback.run(cache -> {
                    try (var zip = new ZipFile(getData())) {
                        var entry = zip.getEntry(value);
                        if (entry == null)
                            throw except("Missing data: `" + key + "`: `" + value + "`");

                        try (var os = new FileOutputStream(target)) {
                            zip.getInputStream(entry).transferTo(os);
                        }
                        target.setLastModified(entry.getLastModifiedTime().toMillis());

                        cache.save();
                    } catch (IOException e) {
                        throw except("Failed to extract `" + key + "`: `" + value + "`");
                    }
                });
            }
        );
    }

    private Task extractFolder(String key, String value) {
        return Task.cachingDir(
            "extract[%s]".formatted(key),
            new File(this.build, "data/" + key),
            (callback, base) -> {
                var existingFiles = FileUtils.listFiles(base);

                callback.setup(cache -> {
                    cache.add("mcp", getData());
                    cache.add(existingFiles);
                });

                callback.run(cache -> {
                    var existing = new HashSet<>(existingFiles);

                    try (var zip = new ZipFile(getData())) {
                        var count = 0;

                        for (var itr = zip.entries(); itr.hasMoreElements(); ) {
                            var e = itr.nextElement();
                            if (e.isDirectory() || !e.getName().startsWith(value))
                                continue;

                            count++;

                            var relative = e.getName().substring(value.length());
                            var target = new File(base, relative);
                            existing.remove(target);
                            FileUtils.ensureParent(target);

                            try (var os = new FileOutputStream(target)) {
                                zip.getInputStream(e).transferTo(os);
                            }
                            target.setLastModified(e.getLastModifiedTime().toMillis());
                        }

                        // Delete files that were already in the target directory which we didn't extract
                        var prefix = base.getAbsolutePath() + File.separator;
                        for (var f : existing) {
                            if (f.exists())
                                f.delete();

                            var parent = f.getAbsoluteFile().getParentFile();
                            if (parent.listFiles().length == 0 &&
                                f.getAbsolutePath().startsWith(prefix)) {
                                parent.delete();
                            }
                        }

                        if (count == 0)
                            throw except("Missing data: `" + key + "`: `" + value + "`");

                        cache.save();
                    } catch (IOException e) {
                        throw except("Failed to extract `" + key + "`: `" + value + "`");
                    }
                });
            }
        );
    }

    private Task createTask(Map<String, String> step) {
        var type = step.get("type");
        var name = step.getOrDefault("name", type);
        var mc = this.side.getMCP().getMinecraftTasks();
        var spec = cfg.spec;

        switch (type) {
            case "downloadManifest": return mc.launcherManifest;
            case "downloadJson": return mc.versionJson;
            case "downloadClient": return mc.versionFile("client", "jar");
            case "downloadServer": return mc.versionFile("server", "jar");
            case "strip": return strip(name, step);
            case "inject": return inject(name, step);
            case "patch": return patch(name, step);
            case "listLibraries":
                if (spec >= 3 && step.containsKey("bundle"))
                    return listLibrariesBundle(name, step);
                return listLibraries(name, step);
        }

        if (spec >= 2) {
            switch (type) {
                case "downloadClientMappings": return mc.versionFile("client_mappings", "txt");
                case "downloadServerMappings": return mc.versionFile("server_mappings", "txt");
            }
        }

        var custom = cfg.getFunction(type);

        if (custom == null)
            throw except("Unknown step type: " + type);

        return execute(name, step, custom);
    }

    private Task strip(String name, Map<String, String> step) {
        var whitelist = "whitelist".equalsIgnoreCase(step.getOrDefault("mode", "whitelist"));
        var input = findStep(step.get("input"));
        return Task.cachingFile(name,
            Task.deps(input, this.mappings),
            new File(this.build, name + ".jar"),
            (c, o) -> strip(c, o, input, whitelist)
        );
    }

    private void strip(Task.Cacheable.Callback callback, File output, Task inputTask, boolean whitelist) {
        var input = inputTask.execute();
        var mappings = this.mappings.execute();

        callback.setup(cache -> {
            cache.add("input", input);
            cache.add("mappings", mappings);
        });

        callback.run(cache -> {
            if (output.exists())
                output.delete();

            try {
                var map = IMappingFile.load(mappings);
                var classes = new HashSet<>();
                for (var cls : map.getClasses())
                    classes.add(cls.getOriginal() + ".class");

                try (var is = new JarInputStream(new FileInputStream(input));
                     var os = new JarOutputStream(new FileOutputStream(output))) {
                    JarEntry entry;
                    while ((entry = is.getNextJarEntry()) != null) {
                        if (entry.isDirectory() || classes.contains(entry.getName()) != whitelist)
                            continue;
                        os.putNextEntry(FileUtils.getStableEntry(entry));
                        is.transferTo(os);
                        os.closeEntry();
                    }
                }
            } catch (IOException e) {
                throw new IOException("Failed to split " + input + " into output " + output, e);
            }
        });
    }

    private Task inject(String name, Map<String, String> step) {
        var input = findStep(step.get("input"));
        var inject = findData("inject");
        var packages = new File(this.build, name + "/packages.jar");
        return Task.cachingFile(name,
            Task.deps(input, inject),
            new File(this.build, name + "/output.jar"),
            (c, o) -> inject(c, o, input, inject, packages)
        );
    }

    private void inject(Task.Cacheable.Callback callback, File output, Task inputTask, Task injectTask, File packages) {
        var input = inputTask.execute();
        var inject = injectTask.execute();

        callback.setup(cache -> cache
            .add("input", input)
            .add("inject", inject));

        callback.run(cache -> {
            if (output.exists())
                output.delete();

            var templateF = new File(input, "package-info-template.java");
            if (templateF.exists()) {
                var modified = templateF.lastModified();
                var template = Files.readString(templateF.toPath(), StandardCharsets.UTF_8);

                var pkgs = new TreeSet<String>();
                try (var zip = new ZipInputStream(new FileInputStream(input))) {
                    ZipEntry entry;
                    while ((entry = zip.getNextEntry()) != null) {
                        var name = entry.getName();
                        if (entry.isDirectory() || !name.endsWith(".java"))
                            continue;

                        var idx = name.indexOf('/');
                        var pkg = idx == -1 ? "" : name.substring(0, idx);

                        // com.mojang was only added in 1.14.4, but I don't feel like making that check and things should be fine.
                        if (pkg.startsWith("net/minecraft/") || pkg.startsWith("com/mojang/"))
                            pkgs.add(pkg);
                    }
                }

                if (!packages.exists())
                    packages.delete();

                try (var zip = new ZipOutputStream(new FileOutputStream(packages))) {
                    for (var pkg : pkgs) {
                        zip.putNextEntry(FileUtils.getStableEntry(pkg + "/package-info.java", modified));
                        zip.write(template.replace("{PACKAGE}", pkg.replace('/', '.')).getBytes(StandardCharsets.UTF_8));
                        zip.closeEntry();
                    }
                }

                FileUtils.mergeJars(output, false, this.injectFileFilter, input, inject, packages);
            } else {
                FileUtils.mergeJars(output, false, this.injectFileFilter, input, inject);
            }
        });
    }

    private Task patch(String name, Map<String, String> step) {
        var input = this.findStep(step.get("input"));
        var patches = this.findData("patches");
        var rejects = new File(this.build, name + "/rejects.jar");
        return Task.cachingFile(name,
            Task.deps(input, patches),
            new File(this.build, name + "/output.jar"),
            (c, o) -> patch(c, o, input, patches, rejects)
        );
    }

    private void patch(Task.Cacheable.Callback callback, File output, Task inputTask, Task patchesTask, File rejects) {
        var input = inputTask.execute();
        var patches = patchesTask.execute();

        callback.setup(cache -> cache
            .add("input", input)
            .add("patches", patches));

        callback.run(cache -> {
            var builder = PatchOperation
                .builder()
                .logTo(Log::error)
                .baseInput(MultiInput.archive(ArchiveFormat.ZIP, input.toPath()))
                .patchesInput(MultiInput.folder(patches.toPath()))
                .patchedOutput(MultiOutput.archive(ArchiveFormat.ZIP, output.toPath()))
                .rejectsOutput(MultiOutput.archive(ArchiveFormat.ZIP, rejects.toPath()))
                .level(LogLevel.ERROR)
                .mode(PatchMode.ACCESS)
                //.aPrefix("a")
                //.bPrefix("b")
                ;

            FileUtils.ensureParent(rejects);

            var result = builder.build().operate();

            boolean success = result.exit == 0;
            if (!success) {
                if (result.summary != null)
                    result.summary.print(Log.ERROR, true);
                else
                    Log.error("Failed to apply patches, no summary available");

                throw except("Failed to apply patches, Rejects saved to: " + rejects.getAbsolutePath());
            }
        });
    }

    private Task listLibraries(String name, Map<String, String> step) {
        Task json = this.findStep("downloadJson");
        return Task.cachingFile(name,
            Task.deps(json),
            new File(this.build, name + ".txt"),
            (c, o) -> listLibraries(c, o, json)
        );
    }

    private void listLibraries(Task.Cacheable.Callback callback, File output, Task jsonTask) {
        var jsonF = jsonTask.execute();
        var json = JsonData.minecraftVersion(jsonF);

        var libs = json.getLibs();
        var libsVarCache = new File(output.getParentFile(), "libraries.txt");

        callback.setup(cache -> {
            cache.add(jsonF);
            cache.add(libsVarCache);
            for (var lib : libs)
                cache.addKnown(lib.coord, lib.dl.sha1);
        });

        callback.checkWith(condition -> condition.and(cache -> {
            if (!libsVarCache.exists()) return false;

            this.libraries = JsonData.<List<Lib.Cached>>fromJson(libsVarCache, new TypeToken<>() { }).stream().map(Lib.Cached::resolve).toList();
            return true;
        }));

        callback.run(cache -> {
            cache.clear().add(jsonF);

            var buf = new StringBuilder(20_000);
            var minecraft = this.side.getMCP().getCache().minecraft();
            var downloadedLibs = new ArrayList<Lib>();
            for (var lib : libs) {
                if (!lib.dl.url.toString().startsWith(Constants.MOJANG_MAVEN))
                    throw new IllegalStateException("Unable to download library " + lib.dl.path + " as it is not on Mojang's repo and I was lazy. " + lib.dl.url);

                var target = minecraft.download(lib.dl);

                buf.append("-e=").append(target.getAbsolutePath()).append('\n');

                var artifact = Artifact.from(lib.coord);
                if (lib.os != null && lib.os != OS.UNKNOWN)
                    artifact = artifact.withOS(lib.os);

                downloadedLibs.add(new Lib(artifact, target));
                cache.add(lib.coord, target);
            }

            FileUtils.ensureParent(libsVarCache);
            JsonData.toJson(downloadedLibs.stream().map(Lib::cacheable).toList(), libsVarCache);
            cache.add(libsVarCache);
            this.libraries = downloadedLibs;

            try (var os = new FileOutputStream(output)) {
                os.write(buf.toString().getBytes(StandardCharsets.UTF_8));
            }
        });
    }

    public record Lib(Artifact name, File file) {
        public Cached cacheable() {
            return new Cached(name, file.getAbsolutePath());
        }

        public record Cached(Artifact name, String file) implements Serializable {
            public Lib resolve() {
                return new Lib(name, new File(file));
            }
        }
    }

    public List<Lib> getLibraries() {
        // no libraries? run the task to populate the field.
        if (this.libraries == null)
            this.findStep("listLibraries").execute();

        return this.libraries;
    }

    private Task listLibrariesBundle(String name, Map<String, String> step) {
        var bundle = findStep(step.get("bundle"));
        var libraries = new File(this.build, name);
        return Task.cachingFile(name,
            Task.deps(bundle),
            new File(this.build, name + "/libraries.txt"),
            (c, o) -> listLibrariesBundle(c, o, bundle, libraries)
        );
    }

    private void listLibrariesBundle(Task.Cacheable.Callback callback, File output, Task bundleTask, File libraries) throws Exception {
        var bundle = bundleTask.execute();

        var jar = new JarFile(bundle);
        var format = jar.getManifest().getMainAttributes().getValue("Bundler-Format");
        if (format == null)
            throw new IllegalStateException("Invalid bundle: `" + bundle + "` - Missing format entry from manifest");

        if (!"1.0".equals(format))
            throw new RuntimeException("Invalid bundle: `" + bundle + "` - Unsupported format " + format);

        var libsListEntry = jar.getEntry("META-INF/libraries.list");
        if (libsListEntry == null)
            throw new IllegalStateException("Invalid bundle: `" + bundle + "` - Missing META-INF/libraries.list");

        record LibLine(String hash, Artifact artifact, String path) implements Comparable<LibLine> {
            @Override
            public int compareTo(LibLine o) {
                return Util.compare(this.artifact, o.artifact);
            }
        }
        var libs = new TreeSet<LibLine>();

        var reader = new BufferedReader(new InputStreamReader(jar.getInputStream(libsListEntry)));
        String line;
        while ((line = reader.readLine()) != null) {
            var pts = line.split("\t");
            if (pts.length < 3)
                throw new IllegalStateException("Invalid bundle: `" + bundle + "` - Invalid line: " + line);
            libs.add(new LibLine(pts[0], Artifact.from(pts[1]), pts[2]));
        }

        callback.setup(cache -> {
            cache.add(bundle);
            for (var lib : libs)
                cache.add(lib.artifact().toString(), new File(libraries, lib.path()));
        });

        callback.run(cache -> {
            cache.clear().add(bundle);

            var buf = new StringBuilder();

            var downloadedLibs = new ArrayList<Lib>();

            for (var lib : libs) {
                var target = new File(libraries, lib.path());
                var artifact = lib.artifact();
                buf.append("-e=").append(target.getAbsolutePath()).append('\n');

                downloadedLibs.add(new Lib(artifact, target));
                if (!target.exists()) {
                    var entry = jar.getEntry("META-INF/libraries/" + lib.path());
                    if (entry == null)
                        throw new IllegalStateException("Invalid bundle: `" + bundle + "` - Missing META-INF/libraries/" + lib);

                    FileUtils.ensureParent(target);

                    try (var os = new FileOutputStream(target);
                         var is = jar.getInputStream(entry)) {
                        is.transferTo(os);
                    }
                }

                cache.add(target);
            }

            this.libraries = Collections.unmodifiableList(downloadedLibs);

            try (var os = new FileOutputStream(output)) {
                os.write(buf.toString().getBytes(StandardCharsets.UTF_8));
            }
        });

        callback.cleanup(didWork -> jar.close());
    }

    public Task getExtra() {
        return Task.cachingFile("extra[" + this.side.getName() + ']',
            Task.deps(this.preStrip, this.mappings),
            new File(this.build, "extra.jar"),
            this::getExtra
        );
    }

    private void getExtra(Task.Cacheable.Callback callback, File output) {
        var preStrip = this.preStrip.execute();
        var mappings = this.mappings.execute();

        callback.setup(cache -> {
            cache.add("prestrip", preStrip);
            cache.add("mappings", mappings);
        });

        callback.run(cache -> {
            var whitelist = IMappingFile
                .load(mappings).getClasses().stream()
                .map(IMappingFile.IClass::getOriginal)
                .collect(Collectors.toSet());
            FileUtils.splitJar(preStrip, whitelist, output, false, false);
        });
    }

    private Task execute(String name, Map<String, String> step, MCPConfig.Function func) {
        var args = new HashMap<String, TaskOrArg>();
        var deps = new LinkedHashSet<Task>();

        // Find any inputs from previous tasks
        for (var entry : step.entrySet()) {
            var key = entry.getKey();
            var value = entry.getValue();
            if (isVariable(value)) {
                var task = value.endsWith("Output}") ? findStep(value) : findData(value);
                deps.add(task);
                args.put(key, new TaskOrArg(value, task, null));
            } else {
                args.put(key, new TaskOrArg(value, null, value));
            }
        }

        // Add the outputs to the possible arg lists
        var ext = step.getOrDefault("outputExtension", "jar");
        var output = new File(this.build, name + '/' + name + '.' + ext);
        var log = new File(this.build, name + "/log.txt");
        args.put("output", new TaskOrArg("output", null, output.getAbsolutePath()));
        args.put("log", new TaskOrArg("log", null, log.getAbsolutePath()));

        // Fill substitutions in arguments, also builds dependencies on extract tasks
        var jvmArgs = fillArgs(func.jvmargs, args, deps);
        var runArgs = fillArgs(func.args, args, deps);

        return Task.cachingFile(name, Task.deps(deps), output,
            (c, o) -> execute(c, jvmArgs, runArgs, func, log)
        );
    }

    private void execute(Task.Cacheable.Callback callback, List<TaskOrArg> jvmArgs, List<TaskOrArg> runArgs, MCPConfig.Function func, File log) {
        // First download the tool
        var maven = new MavenCache("mcp-tools", func.repo, this.side.getMCP().getCache().root());
        var toolA = Artifact.from(func.version);
        var tool = maven.download(toolA);

        var tasks = new HashMap<Task, String>();
        var jvm = new ArrayList<String>();
        var run = new ArrayList<String>();

        callback.setup(cache -> {
            cache.add("tool", tool);
            cache.add("jvm-args", jvmArgs.stream().map(TaskOrArg::name).collect(Collectors.joining(" ")));
            cache.add("run-args", runArgs.stream().map(TaskOrArg::name).collect(Collectors.joining(" ")));
            resolveArgs(jvm, cache, tasks, jvmArgs);
            resolveArgs(run, cache, tasks, runArgs);
        });

        callback.run(cache -> {
            int java_version = func.getJavaVersion(this.side.getMCP().getConfig());
            var jdks = this.side.getMCP().getCache().jdks();
            var jdk = jdks.get(java_version);
            if (jdk == null)
                throw new IllegalStateException("Failed to find JDK for version " + java_version);

            var ret = ProcessUtils.runJar(jdk, log.getParentFile(), log, tool, jvm, run);
            if (ret.exitCode != 0)
                throw new IllegalStateException("Failed to run MCP Step, See log: " + log.getAbsolutePath());
        });
    }

    private boolean isVariable(String value) {
        return value.startsWith("{") && value.endsWith("}");
    }

    private record TaskOrArg(String name, Task task, String value) { }

    private List<TaskOrArg> fillArgs(List<String> lst, Map<String, TaskOrArg> args, SequencedSet<Task> deps) {
        if (lst == null)
            return List.of();

        var ret = new ArrayList<TaskOrArg>(lst.size());
        for (var value : lst) {
            if (isVariable(value)) {
                var data_name = value.substring(1, value.length() - 1);
                var arg = args.get(data_name);
                if (arg != null)
                    ret.add(arg);
                else {
                    var task = this.findData(data_name);
                    deps.add(task);
                    ret.add(new TaskOrArg(data_name, task, null));
                }
            } else
                ret.add(new TaskOrArg(value, null, value));
        }
        return ret;
    }

    private List<String> resolveArgs(ArrayList<String> ret, HashStore cache, Map<Task, String> tasks, List<TaskOrArg> args) {
        for (var toa : args) {
            if (toa.task() == null)
                ret.add(toa.value());
            else {
                var path = tasks.get(toa.task());
                if (path == null) {
                    var file = toa.task().execute();
                    cache.add(toa.name(), file);
                    path = file.getAbsolutePath();
                }
                ret.add(path);
            }
        }
        return ret;
    }

}
