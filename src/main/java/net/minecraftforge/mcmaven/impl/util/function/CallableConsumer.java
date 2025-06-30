/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mcmaven.impl.util.function;

@FunctionalInterface
public interface CallableConsumer<T> {
    void accept(T t) throws Exception;
}
