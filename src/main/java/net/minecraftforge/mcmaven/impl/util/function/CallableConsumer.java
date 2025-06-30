/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.util.function;

@FunctionalInterface
public interface CallableConsumer<T> {
    static <T> CallableConsumer<T> empty() {
        return t -> { };
    }

    void accept(T t) throws Exception;
}
