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

package io.github.gunpowder

import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import eu.pb4.permissions.api.v0.PermissionProvider
import eu.pb4.permissions.api.v0.PermissionValue
import eu.pb4.permissions.api.v0.UserContext
import io.github.gunpowder.api.GunpowderMod
import io.github.gunpowder.entities.PermissionTree
import io.github.gunpowder.models.GroupPermissionTable
import io.github.gunpowder.models.GroupUsers
import io.github.gunpowder.models.UserPermissionTable
import net.minecraft.command.CommandSource
import net.minecraft.nbt.NbtCompound
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.world.ServerWorld
import org.jetbrains.exposed.sql.*
import java.time.Duration
import java.util.concurrent.CompletableFuture


object GunpowderPermissionProvider : PermissionProvider, SuggestionProvider<ServerCommandSource> {
    private val db by lazy { GunpowderMod.instance.database }
    val known = mutableSetOf<String>()

    /**
     * Add all permissions to ensure they're visible
     */
    fun registerPermissions(perms: List<String>) {
        known.addAll(perms)
    }

    override fun getName() = "Gunpowder Permissions"

    override fun getIdentifier() = "gunpowder-permissions"

    override fun supportsGroups() = true

    override fun supportsTemporaryPermissions() = false

    override fun supportsTimedGroups() = false

    override fun supportsPerWorldPermissions(): Boolean = false

    override fun supportsPerWorldGroups(): Boolean = false
    override fun supportsOfflineChecks(): Boolean {
        TODO("Not yet implemented")
    }

    override fun supportsChangingPlayersPermissions(): Boolean {
        TODO("Not yet implemented")
    }

    override fun getPriority(): PermissionProvider.Priority = PermissionProvider.Priority.MAIN

    override fun check(user: UserContext, permission: String): PermissionValue {
        if (!permission.endsWith(".?")) {
            known.add(permission)
        }

        var state = db.transaction {
            if (UserPermissionTable.select { UserPermissionTable.user.eq(user.uuid) }.firstOrNull()?.let {
                    PermissionTree.fromNbt(it[UserPermissionTable.permissionTree]).nodes()
                        .any { n -> n.permits(permission) }
                } == true) {
                PermissionValue.TRUE
            } else {
                PermissionValue.DEFAULT
            }
        }.get()

        if (state != PermissionValue.TRUE) {
            for (group in getGroups(user)) {
                state = checkGroup(group, permission)
                if (state == PermissionValue.TRUE) {
                    break
                }
            }
        }
        return state
    }

    override fun getList(user: UserContext, world: ServerWorld?, value: PermissionValue): List<String> {
        if (value != PermissionValue.TRUE) {
            return emptyList()
        }

        return db.transaction {
            val row = UserPermissionTable.select { UserPermissionTable.user.eq(user.uuid) }.firstOrNull()
            val perms = mutableListOf<String>()
            if (row != null) {
                perms.addAll(
                    PermissionTree.fromNbt(row[UserPermissionTable.permissionTree]).nodes().reversed()
                        .map { it.path() })
            }
            for (group in GroupUsers.select { GroupUsers.user.eq(user.uuid) }) {
                val grouprow =
                    GroupPermissionTable.select { GroupPermissionTable.name.eq(group[GroupUsers.group]) }.first()
                perms.addAll(
                    PermissionTree.fromNbt(grouprow[GroupPermissionTable.permissionTree]).nodes().reversed()
                        .map { it.path() })
            }
            perms
        }.get()
    }

    override fun getList(
        user: UserContext,
        parentPermission: String,
        world: ServerWorld?,
        value: PermissionValue
    ): List<String> {
        return getList(user, world, value).filter { it.startsWith(parentPermission) }
            .map { it.removePrefix(parentPermission) }
    }

    override fun getListNonInherited(
        user: UserContext,
        world: ServerWorld?,
        value: PermissionValue
    ): List<String> {
        return db.transaction {
            val row = UserPermissionTable.select { UserPermissionTable.user.eq(user.uuid) }.firstOrNull()
            if (row != null && value == PermissionValue.TRUE) {
                PermissionTree.fromNbt(row[UserPermissionTable.permissionTree]).nodes().reversed().map { it.path() }
                    .toList()
            } else {
                emptyList()
            }
        }.get()
    }

    override fun getListNonInherited(
        user: UserContext,
        parentPermission: String,
        world: ServerWorld?,
        value: PermissionValue
    ): List<String> {
        return getListNonInherited(user, world, value).filter { it.startsWith(parentPermission) }
            .map { it.removePrefix(parentPermission) }
    }

    override fun getAll(user: UserContext, world: ServerWorld?): Map<String, PermissionValue> {
        return getList(user, world, PermissionValue.TRUE).associateWith { PermissionValue.TRUE }
    }

    override fun getAll(
        user: UserContext,
        parentPermission: String,
        world: ServerWorld?
    ): Map<String, PermissionValue> {
        return getList(user, parentPermission, world, PermissionValue.TRUE).associateWith { PermissionValue.TRUE }
    }

    override fun getAllNonInherited(user: UserContext, world: ServerWorld?): Map<String, PermissionValue> {
        return getListNonInherited(user, world).associateWith { PermissionValue.TRUE }
    }

    override fun getAllNonInherited(
        user: UserContext,
        parentPermission: String,
        world: ServerWorld?
    ): Map<String, PermissionValue> {
        return getListNonInherited(user, parentPermission, world).associateWith { PermissionValue.TRUE }
    }

    override fun set(user: UserContext, world: ServerWorld?, permission: String, value: PermissionValue) {
        db.transaction {
            val row = UserPermissionTable.select { UserPermissionTable.user.eq(user.uuid) }.firstOrNull()

            if (row == null) {
                val tree = PermissionTree("root", mutableListOf()).also {
                    if (value == PermissionValue.TRUE) {
                        it.getOrCreate(permission)
                    } else {
                        it.remove(permission)
                    }
                }

                UserPermissionTable.insert {
                    it[UserPermissionTable.user] = user.uuid
                    it[UserPermissionTable.permissionTree] = NbtCompound().also { nbt -> tree.writeNbt(nbt) }
                }
            } else {
                val tree = PermissionTree.fromNbt(row[UserPermissionTable.permissionTree]).also {
                    if (value == PermissionValue.TRUE) {
                        it.getOrCreate(permission)
                    } else {
                        it.remove(permission)
                    }
                }

                UserPermissionTable.update({ UserPermissionTable.user.eq(user.uuid) }) {
                    it[UserPermissionTable.user] = user.uuid
                    it[UserPermissionTable.permissionTree] = NbtCompound().also { nbt -> tree.writeNbt(nbt) }
                }
            }
        }
    }

    override fun set(
        user: UserContext,
        world: ServerWorld?,
        permission: String,
        value: PermissionValue,
        duration: Duration
    ) {
        set(user, world, permission, value)
    }

    override fun getGroups(user: UserContext, world: ServerWorld?): List<String> {
        return db.transaction {
            GroupUsers.select { GroupUsers.user.eq(user.uuid) }.map {
                it[GroupUsers.group]
            }.toList()
        }.get()
    }

    override fun addGroup(user: UserContext, world: ServerWorld?, group: String) {
        db.transaction {
            GroupUsers.insert {
                it[GroupUsers.user] = user.uuid
                it[GroupUsers.group] = group
            }
        }
    }

    override fun addGroup(user: UserContext, world: ServerWorld?, group: String, duration: Duration) {
        addGroup(user, world, group)
    }

    override fun removeGroup(user: UserContext, world: ServerWorld?, group: String) {
        if (group == "everyone") {
            throw IllegalArgumentException("Cannot remove player from group 'everyone'!")
        }

        db.transaction {
            GroupUsers.deleteWhere { GroupUsers.user.eq(user.uuid) and GroupUsers.group.eq(group) }
        }
    }

    override fun checkGroup(group: String, world: ServerWorld?, permission: String): PermissionValue {
        if (!permission.endsWith(".?")) {
            known.add(permission)
        }

        return db.transaction {
            if (GroupPermissionTable.select { GroupPermissionTable.name.eq(group) }.firstOrNull()?.let {
                    PermissionTree.fromNbt(it[GroupPermissionTable.permissionTree]).nodes()
                        .any { n -> n.permits(permission) }
                } == true) {
                PermissionValue.TRUE
            } else {
                PermissionValue.DEFAULT
            }
        }.get()
    }

    override fun getListGroup(group: String, world: ServerWorld?, value: PermissionValue): List<String> {
        return db.transaction {
            val row = GroupPermissionTable.select { GroupPermissionTable.name.eq(group) }.firstOrNull()
            if (row != null && value == PermissionValue.TRUE) {
                PermissionTree.fromNbt(row[GroupPermissionTable.permissionTree]).nodes().reversed().map { it.path() }
                    .toList()
            } else {
                emptyList()
            }
        }.get()
    }

    override fun getListGroup(
        group: String,
        parentPermission: String,
        world: ServerWorld?,
        value: PermissionValue
    ): List<String> {
        return getListGroup(group, world, value).filter { it.startsWith(parentPermission) }
            .map { it.removePrefix(parentPermission) }.toList()
    }

    override fun getListNonInheritedGroup(
        group: String,
        world: ServerWorld?,
        value: PermissionValue
    ): List<String> {
        return getListGroup(group, world, value)
    }

    override fun getListNonInheritedGroup(
        group: String,
        parentPermission: String,
        world: ServerWorld?,
        value: PermissionValue
    ): List<String> {
        return getListGroup(group, parentPermission, world, value)
    }

    override fun getAllGroup(group: String, world: ServerWorld?): Map<String, PermissionValue> {
        return getListGroup(group, world, PermissionValue.TRUE).associateWith { PermissionValue.TRUE }
    }

    override fun getAllGroup(
        group: String,
        parentPermission: String,
        world: ServerWorld?
    ): Map<String, PermissionValue> {
        return getListGroup(group, parentPermission, world, PermissionValue.TRUE).associateWith { PermissionValue.TRUE }
    }

    override fun getAllNonInheritedGroup(group: String, world: ServerWorld?): Map<String, PermissionValue> {
        return getAllGroup(group, world)
    }

    override fun getAllNonInheritedGroup(
        group: String,
        parentPermission: String,
        world: ServerWorld?
    ): Map<String, PermissionValue> {
        return getAllGroup(group, parentPermission, world)
    }

    override fun getSuggestions(
        context: CommandContext<ServerCommandSource>?,
        builder: SuggestionsBuilder?
    ): CompletableFuture<Suggestions> {
        return CommandSource.suggestMatching(known.sorted(), builder)
    }
}