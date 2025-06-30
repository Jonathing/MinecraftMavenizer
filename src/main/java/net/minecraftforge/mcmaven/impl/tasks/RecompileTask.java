/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.tasks;

import net.minecraftforge.mcmaven.impl.mappings.Mappings;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCP;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.mcmaven.impl.util.ProcessUtils;
import net.minecraftforge.mcmaven.impl.util.Task;

import java.io.File;
import java.util.List;
import java.util.function.Supplier;

/**
 * Takes a jar containing java source files.
 * A list of libraries
 * And recompiles them to a jar file containing all class files.
 */
public final class RecompileTask implements Task {
    private final File build;
    private final Artifact name;
    private final MCP mcp;
    private final Supplier<List<File>> classpath;
    private final Mappings mappings;
    private final Task task;

    public RecompileTask(File build, Artifact name, MCP mcp, Supplier<List<File>> classpath, Task sources, Mappings mappings) {
        this.build = mappings.getFolder(build);
        this.name = name;
        this.mcp = mcp;
        this.classpath = classpath;
        this.mappings = mappings;
        this.task = this.recompileSources(sources);
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

    protected Task recompileSources(Task input) {
        return Task.cachingFile("recompile[" + this.name.getName() + "][" + this.mappings + ']',
            Task.deps(input),
            new File(this.build, "recompiled.jar"),
            (callback, output) -> recompileSourcesImpl(callback, output, input)
        );
    }

    private void recompileSourcesImpl(Cacheable.Callback callback, File output, Task input) {
        var javaTarget = this.mcp.getConfig().java_target;
        var sourcesJar = input.execute();

        callback.setup(cache -> cache.add("sources", sourcesJar));

        callback.run(cache -> {
            var jdk = this.mcp.getCache().jdks().get(javaTarget);
            if (jdk == null) {
                throw new IllegalStateException("JDK not found: " + javaTarget);
            }

            ProcessUtils.recompileJar(jdk, this.classpath.get(), sourcesJar, output, this.build);
        });
    }
}
