/*
 * MIT License
 *
 * Copyright (c) 2020 GunpowderMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.github.gunpowder.entities

import net.fabricmc.fabric.api.util.NbtType
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtList

data class PermissionTree(
    val name: String,
    val children: MutableList<PermissionTree>
) {
    fun remove(permission: String) {
        val parts = permission.split('.').toMutableList()
        val child = getOrCreateChild(parts.removeFirst())
        if (parts.isEmpty()) {
            children.remove(child)
        } else {
            child.remove(parts.joinToString("."))
        }
    }

    fun getOrCreate(permission: String) {
        val parts = permission.split('.').toMutableList()
        getOrCreateChild(parts.removeFirst()).getOrCreate(parts.joinToString("."))
    }

    fun getOrCreateChild(name: String): PermissionTree {
        if (this.name.toIntOrNull() != null) {
            throw IllegalStateException("Nodes with numerical values cannot have child nodes")
        }

        return children.firstOrNull { it.name == name } ?: PermissionTree(name, mutableListOf()).also {
            children.add(it)
        }
    }

    fun nodes(): List<PermissionNode> {
        val items = mutableListOf<PermissionNode>()
        if (name == "root") {
            val self = PermissionNode(name, null)
            items.add(self)
        }
        for (child in children) {
            items.addAll(child.nodes())
        }
        return items
    }

    fun writeNbt(nbtCompound: NbtCompound) {
        val nested = NbtList()
        for (child in children) {
            val nest = NbtCompound()
            child.writeNbt(nest)
            nested.add(nest)
        }
        nbtCompound.putString("name", name)
        nbtCompound.put("children", nested)
    }

    companion object {
        fun fromNbt(nbtCompound: NbtCompound): PermissionTree = PermissionTree(
            nbtCompound.getString("name"),
            nbtCompound.getList("children", NbtType.COMPOUND).map { fromNbt(it as NbtCompound) }.toMutableList()
        )
    }
}
