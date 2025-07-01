/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.repo.forge;

import net.minecraftforge.mcmaven.impl.cache.Cache;
import net.minecraftforge.mcmaven.impl.mappings.Mappings;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.util.file.FileUtils;
import net.minecraftforge.mcmaven.impl.util.Task;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Takes a jar containing compiled class files, and injects extra data/resources from a patcher into it.
 */
public final class InjectTask implements Task {
    private final File build;
    private final Artifact name;
    private final Cache cache;
    private final Patcher patcher;
    private final Mappings mappings;
    private final Task task;

    InjectTask(File build, Cache cache, Artifact name, Patcher patcher, Task input, Mappings mappings) {
        this.build = mappings.getFolder(build);
        this.name = name;
        this.cache = cache;
        this.patcher = patcher;
        this.mappings = mappings;
        this.task = this.injectData(input);
    }

    @Override
    public File execute() {
        return this.task.execute();
    }

    @Override
    public boolean isResolved() {
        return this.task.isResolved();
    }

    @Override
    public String getName() {
        return this.task.getName();
    }

    private Task injectData(Task input) {
        return Task.cachingFile("injectData[" + this.name.getName() + "][" + mappings + ']',
            Task.deps(input),
            new File(this.build, "injected.jar"),
            (c, o) -> injectDataImpl(c, o, input)
        );
    }

    private void injectDataImpl(Cacheable.Callback callback, File outputJar, Task inputTask) {
        var recompiledJar = inputTask.execute();
        var universals = new ArrayList<File>();

        callback.setup(cache -> {
            cache.add("recompiled", recompiledJar);

            for (var p : this.patcher.getStack()) {
                if (p.config.universal != null) {
                    var universal = this.cache.maven().download(Artifact.from(p.config.universal));
                    universals.add(universal);
                    cache.add(universal);
                }
            }
        });

        callback.run(cache -> {
            var jars = new ArrayList<>(universals);
            jars.add(recompiledJar);

            FileUtils.mergeJars(outputJar, true, (file, name) -> file == recompiledJar || !name.endsWith(".class"), jars.toArray(File[]::new));
        });
    }
}
