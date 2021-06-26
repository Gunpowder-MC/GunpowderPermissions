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

package io.github.gunpowder.commands

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import eu.pb4.permissions.api.v0.PermissionValue
import eu.pb4.permissions.api.v0.Permissions
import eu.pb4.permissions.api.v0.UserContext
import io.github.gunpowder.GunpowderPermissionProvider
import io.github.gunpowder.api.GunpowderMod
import io.github.gunpowder.api.builders.Command
import io.github.gunpowder.entities.PermissionArgumentType
import io.github.gunpowder.entities.PermissionTree
import io.github.gunpowder.models.GroupPermissionTable
import io.github.gunpowder.models.UserPermissionTable
import net.minecraft.command.CommandSource
import net.minecraft.command.argument.GameProfileArgumentType
import net.minecraft.nbt.NbtCompound
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.LiteralText
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update

object PermissionCommand {
    private val db by lazy { GunpowderMod.instance.database }

    fun register(dispatcher: CommandDispatcher<ServerCommandSource>) {
        Command.builder(dispatcher) {
            command("permission") {
                requires { Permissions.require("permissions.edit.?", 4).test(it) }

                literal("group") {
                    requires { Permissions.require("permissions.edit.group.?", 4).test(it) }

                    argument("group", StringArgumentType.string()) {
                        literal("grant") {
                            requires { Permissions.require("permissions.edit.group.grant", 4).test(it) }
                            argument("permission", PermissionArgumentType.permission()) {
                                executes(::grantGroup)
                                suggests { ctx, builder ->
                                    val group = StringArgumentType.getString(ctx, "group")
                                    val items = db.transaction {
                                        val row = GroupPermissionTable.select { GroupPermissionTable.name.eq(group) }.firstOrNull()
                                        val items = if (row == null) {
                                            emptyList()
                                        } else {
                                            PermissionTree.fromNbt(row[GroupPermissionTable.permissionTree]).nodes().map { it.path() }.toList()
                                        }
                                        items
                                    }.get()
                                    CommandSource.suggestMatching(
                                        GunpowderPermissionProvider.known.filter { it !in items }.sorted(),
                                        builder
                                    )
                                }
                            }
                        }

                        literal("revoke") {
                            requires { Permissions.require("permissions.edit.group.revoke", 4).test(it) }
                            argument("permission", PermissionArgumentType.permission()) {
                                executes(::revokeGroup)
                                suggests { ctx, builder ->
                                    val group = StringArgumentType.getString(ctx, "group")
                                    val items = db.transaction {
                                        val row = GroupPermissionTable.select { GroupPermissionTable.name.eq(group) }.firstOrNull()
                                        val items = if (row == null) {
                                            emptyList()
                                        } else {
                                            PermissionTree.fromNbt(row[GroupPermissionTable.permissionTree]).nodes().map { it.path() }.toList()
                                        }
                                        items
                                    }.get()
                                    CommandSource.suggestMatching(
                                        items.sorted(),
                                        builder
                                    )
                                }
                            }
                        }

                        literal("list") {
                            requires { Permissions.require("permissions.edit.group.list", 4).test(it) }
                            executes(::listGroup)
                        }
                    }
                }

                literal("player") {
                    requires { Permissions.require("permissions.edit.player.?", 4).test(it) }

                    argument("player", GameProfileArgumentType.gameProfile()) {
                        literal("grant") {
                            requires { Permissions.require("permissions.edit.player.grant", 4).test(it) }
                            argument("permission", PermissionArgumentType.permission()) {
                                executes(::grantPlayer)
                                suggests { ctx, builder ->
                                    val player = GameProfileArgumentType.getProfileArgument(ctx, "player").first()
                                    val items = db.transaction {
                                        val row = UserPermissionTable.select { UserPermissionTable.user.eq(player.id) }.firstOrNull()
                                        val items = if (row == null) {
                                            emptyList()
                                        } else {
                                            PermissionTree.fromNbt(row[UserPermissionTable.permissionTree]).nodes().map { it.path() }.toList()
                                        }
                                        items
                                    }.get()
                                    CommandSource.suggestMatching(
                                        GunpowderPermissionProvider.known.filter { it !in items }.sorted(),
                                        builder
                                    )
                                }
                            }
                        }

                        literal("revoke") {
                            requires { Permissions.require("permissions.edit.player.revoke", 4).test(it) }
                            argument("permission", PermissionArgumentType.permission()) {
                                executes(::revokePlayer)
                                suggests { ctx, builder ->
                                    val player = GameProfileArgumentType.getProfileArgument(ctx, "player").first()
                                    val items = db.transaction {
                                        val row = UserPermissionTable.select { UserPermissionTable.user.eq(player.id) }.firstOrNull()
                                        val items = if (row == null) {
                                            emptyList()
                                        } else {
                                            PermissionTree.fromNbt(row[UserPermissionTable.permissionTree]).nodes().map { it.path() }.toList()
                                        }
                                        items
                                    }.get()
                                    CommandSource.suggestMatching(
                                        items.sorted(),
                                        builder
                                    )
                                }
                            }
                        }

                        literal("list") {
                            requires { Permissions.require("permissions.edit.player.list", 4).test(it) }
                            executes(::listPlayer)
                        }
                    }
                }
            }
        }
    }

    private fun grantPlayer(context: CommandContext<ServerCommandSource>): Int {
        val player = GameProfileArgumentType.getProfileArgument(context, "player").first()
        val permission = PermissionArgumentType.getPermission(context, "permission")

        GunpowderPermissionProvider.set(UserContext.of(player), null, permission, PermissionValue.TRUE)
        context.source.sendFeedback(LiteralText("Permission granted"), false)

        return 0
    }

    private fun revokePlayer(context: CommandContext<ServerCommandSource>): Int {
        val player = GameProfileArgumentType.getProfileArgument(context, "player").first()
        val permission = PermissionArgumentType.getPermission(context, "permission")

        GunpowderPermissionProvider.set(UserContext.of(player), null, permission, PermissionValue.DEFAULT)
        context.source.sendFeedback(LiteralText("Permission revoked"), false)

        return 0
    }

    private fun listPlayer(context: CommandContext<ServerCommandSource>): Int {
        val player = GameProfileArgumentType.getProfileArgument(context, "player").first()

        val permissions = GunpowderPermissionProvider.getList(UserContext.of(player))
        context.source.sendFeedback(LiteralText("Permissions:\n" + permissions.joinToString("\n") { "- $it" }), false)

        return 0
    }

    private fun grantGroup(context: CommandContext<ServerCommandSource>): Int {
        val group = StringArgumentType.getString(context, "group")
        val permission = PermissionArgumentType.getPermission(context, "permission")

        db.transaction {
            val row = GroupPermissionTable.select { GroupPermissionTable.name.eq(group) }.firstOrNull()

            if (row == null) {
                val tree = PermissionTree("root", mutableListOf()).also {
                    it.getOrCreate(permission)
                }

                GroupPermissionTable.insert {
                    it[GroupPermissionTable.name] = group
                    it[GroupPermissionTable.permissionTree] = NbtCompound().also { nbt -> tree.writeNbt(nbt) }
                }
            } else {
                val tree = PermissionTree.fromNbt(row[UserPermissionTable.permissionTree]).also {
                    it.getOrCreate(permission)
                }

                GroupPermissionTable.update({ GroupPermissionTable.name.eq(group) }) {
                    it[GroupPermissionTable.name] = group
                    it[GroupPermissionTable.permissionTree] = NbtCompound().also { nbt -> tree.writeNbt(nbt) }
                }
            }
        }

        context.source.sendFeedback(LiteralText("Permission granted"), false)

        return 0
    }

    private fun revokeGroup(context: CommandContext<ServerCommandSource>): Int {
        val group = StringArgumentType.getString(context, "group")
        val permission = PermissionArgumentType.getPermission(context, "permission")

        db.transaction {
            val row = GroupPermissionTable.select { GroupPermissionTable.name.eq(group) }.firstOrNull()

            if (row == null) {
                val tree = PermissionTree("root", mutableListOf()).also {
                    it.remove(permission)
                }

                GroupPermissionTable.insert {
                    it[GroupPermissionTable.name] = group
                    it[GroupPermissionTable.permissionTree] = NbtCompound().also { nbt -> tree.writeNbt(nbt) }
                }
            } else {
                val tree = PermissionTree.fromNbt(row[UserPermissionTable.permissionTree]).also {
                    it.remove(permission)
                }

                GroupPermissionTable.update({ GroupPermissionTable.name.eq(group) }) {
                    it[GroupPermissionTable.name] = group
                    it[GroupPermissionTable.permissionTree] = NbtCompound().also { nbt -> tree.writeNbt(nbt) }
                }
            }
        }

        context.source.sendFeedback(LiteralText("Permission revoked"), false)

        return 0
    }

    private fun listGroup(context: CommandContext<ServerCommandSource>): Int {
        val group = StringArgumentType.getString(context, "group")

        val permissions = GunpowderPermissionProvider.getListGroup(group)
        context.source.sendFeedback(LiteralText("Permissions:\n" + permissions.joinToString("\n") { "- $it" }), false)

        return 0
    }
}
