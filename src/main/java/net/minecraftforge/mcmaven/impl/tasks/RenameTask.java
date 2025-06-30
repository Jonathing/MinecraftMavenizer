/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.tasks;

import net.minecraftforge.mcmaven.impl.mappings.Mappings;
import net.minecraftforge.mcmaven.impl.repo.mcpconfig.MCPSide;
import net.minecraftforge.mcmaven.impl.util.Artifact;
import net.minecraftforge.mcmaven.impl.util.Task;
import net.minecraftforge.util.file.FileUtils;
import net.minecraftforge.util.hash.HashFunction;
import net.minecraftforge.util.hash.HashUtils;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Takes a input jar file filled with SRG named sources.
 * Applies mappings provided by a ziped csv file to them and returns the new sources.
 */
public final class RenameTask implements Task {
    private final String name;
    private final MCPSide side;
    private final Task task;

    /**
     * Creates a new renamer for the given patcher.
     *
     * @param build   The directory where the output will be stored
     * @param name    The development artifact, only used for the task name
     * @param sources The task that creates the unnamed sources
     */
    public RenameTask(File build, Artifact name, MCPSide side, Task sources, Mappings mappings) {
        this(build, name.getName(), side, sources, mappings);
    }

    /**
     * Creates a new renamer for the given patcher.
     *
     * @param build   The directory where the output will be stored
     * @param name    The development artifact, only used for the task name
     * @param sources The task that creates the unnamed sources
     */
    public RenameTask(File build, String name, MCPSide side, Task sources, Mappings mappings) {
        this.name = name;
        this.side = side;
        this.task = this.remapSources(sources, mappings.getFolder(build), mappings);
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

    private Task remapSources(Task input, File outputDir, Mappings provider) {
        var mappings = provider.getCsvZip(side);
        return Task.cachingFile("remap[" + this.name + "][" + provider + ']',
            Task.deps(input, mappings),
            new File(outputDir, "remapped.jar"),
            (callback, output) -> remapSourcesImpl(callback, output, input, mappings)
        );
    }

    private static void remapSourcesImpl(Cacheable.Callback callback, File output, Task inputTask, Task mappingsTask) {
        var input = inputTask.execute();
        var mappings = mappingsTask.execute();

        callback.setup(cache -> {
            cache.add("input", input);
            cache.add("mappings", mappings);
        });

        callback.run(cache -> {
            try {
                var names = MCPNames.load(mappings);
                names.rename(new FileInputStream(input), true);

                // TODO: [MCMavenizer][Renamer] This garbage was copy-pasted from FG.
                //  I changed the while loop to a for loop, though. I guess it is fine?
                try (var zin = new ZipInputStream(new FileInputStream(input));
                     var zout = new ZipOutputStream(new FileOutputStream(output))) {
                    for (var entry = zin.getNextEntry(); entry != null; entry = zin.getNextEntry()) {
                        zout.putNextEntry(FileUtils.getStableEntry(entry.getName()));

                        if (entry.getName().endsWith(".java")) {
                            var mapped = names.rename(zin, false);
                            IOUtils.write(mapped, zout, StandardCharsets.UTF_8);
                        } else {
                            IOUtils.copy(zin, zout);
                        }
                    }
                }

                HashUtils.updateHash(output, HashFunction.SHA1);
            } catch (IOException e) {
                throw new RuntimeException("Failed to rename sources for " + input.getAbsolutePath(), e);
            }
        });
    }
}
