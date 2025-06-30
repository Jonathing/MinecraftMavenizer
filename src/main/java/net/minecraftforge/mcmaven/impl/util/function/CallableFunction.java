/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.util.function;

@FunctionalInterface
public interface CallableFunction<T, R> {
    static <T> CallableFunction<T, T> identity() {
        return t -> t;
    }

    R accept(T t) throws Exception;
}
