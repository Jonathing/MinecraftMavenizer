/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.repo.mcpconfig;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraftforge.mcmaven.impl.GlobalOptions;
import net.minecraftforge.mcmaven.impl.util.Constants;
import net.minecraftforge.mcmaven.impl.util.Task;
import net.minecraftforge.util.data.json.JsonData;
import net.minecraftforge.util.download.DownloadUtils;

/** Handles Minecraft-specific tasks, unrelated to the MCPConfig toolchain. */
public class MinecraftTasks {
    private final File cache;
    private final String version;
    public final Task launcherManifest;
    public final Task versionJson;
    private final Map<String, Task> versionFiles = new HashMap<>();

    /**
     * Creates a new Minecraft task handler for the given version.
     *
     * @param cache   The caches folder
     * @param version The version to handle tasks for
     */
    MinecraftTasks(File cache, String version) {
        this.cache = new File(cache, "minecraft_tasks");
        this.version = version;
        this.launcherManifest = Task.cachingFile("downloadLauncherManifest",
            new File(this.cache, "launcher_manifest.json"),
            this::downloadLauncherManifest);
        this.versionJson = Task.cachingFile("downloadVersionJson[" + version + ']',
            Task.deps(this.launcherManifest),
            new File(this.cache, this.version + "/version.json"),
            (callback, target) -> this.downloadVersionJson(callback, target, this.launcherManifest));
    }

    private void downloadLauncherManifest(Task.Cacheable.Callback callback, File target) {
        callback.checkWith(condition ->
            condition.and(c -> GlobalOptions.isCacheOnly() || target.lastModified() >= System.currentTimeMillis() - Constants.CACHE_TIMEOUT)
        );

        callback.run(cache -> {
            GlobalOptions.assertOnline();
            DownloadUtils.downloadFile(target, Constants.LAUNCHER_MANIFEST);
        });
    }

    private void downloadVersionJson(Task.Cacheable.Callback callback, File target, Task manifestTask) {
        var manifestF = manifestTask.execute();

        callback.setup(cache -> cache.add("manifest", manifestF));

        callback.run(cache -> {
            var manifest = JsonData.launcherManifest(manifestF);
            var url = manifest.getUrl(this.version);
            if (url == null)
                throw new IllegalStateException("Failed to find url for " + this.version + " version.json");

            GlobalOptions.assertOnline();
            DownloadUtils.downloadFile(false, target, url.toExternalForm());
        });
    }

    public Task versionFile(String key, String ext) {
        return this.versionFiles.computeIfAbsent(key, k ->
            Task.cachingFile("download[" + this.version + "][" + key + ']',
                Task.deps(() -> this.versionJson),
                new File(this.cache, this.version + '/' + key  + '.' + ext),
                (callback, target) -> downloadVersionFile(callback, target, key, ext)
            )
        );
    }

    private void downloadVersionFile(Task.Cacheable.Callback callback, File target, String key, String ext) {
        var versionJsonF = this.versionJson.execute();

        callback.setup(cache -> cache.add("versionJson", versionJsonF));

        callback.run(cache -> {
            var versionJson = JsonData.minecraftVersion(versionJsonF);
            var dl = versionJson.getDownload(key);
            if (dl == null || dl.url == null)
                throw new IllegalStateException("Missing '" + key  +"' from " + versionJsonF.getAbsolutePath());

            GlobalOptions.assertOnline();
            DownloadUtils.downloadFile(target, dl.url.toExternalForm());
        });
    }
}
