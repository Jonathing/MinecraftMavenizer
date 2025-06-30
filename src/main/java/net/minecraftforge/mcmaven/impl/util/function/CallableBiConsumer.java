/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.util.function;

@FunctionalInterface
public interface CallableBiConsumer<T, U> {
    static <T, U> CallableBiConsumer<T, U> empty() {
        return (t, u) -> { };
    }

    void accept(T t, U u) throws Exception;
}
