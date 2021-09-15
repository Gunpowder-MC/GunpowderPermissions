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
import eu.pb4.permissions.api.v0.Permissions
import io.github.gunpowder.api.GunpowderMod
import io.github.gunpowder.api.builders.Command
import io.github.gunpowder.entities.PermissionTree
import io.github.gunpowder.models.GroupPermissionTable
import io.github.gunpowder.models.GroupUsers
import net.minecraft.command.argument.GameProfileArgumentType
import net.minecraft.nbt.NbtCompound
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.LiteralText
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select

object GroupCommand {
    private val db by lazy { GunpowderMod.instance.database }

    fun register(dispatcher: CommandDispatcher<ServerCommandSource>) {
        Command.builder(dispatcher) {
            command("group") {
                requires { Permissions.require("permissions.groups.?", 3).test(it) }

                literal("create") {
                    requires { Permissions.require("permissions.groups.create", 4).test(it) }
                    argument("name", StringArgumentType.string()) {
                        executes(::create)
                    }
                }

                literal("delete") {
                    requires { Permissions.require("permissions.groups.delete", 4).test(it) }
                    argument("name", StringArgumentType.string()) {
                        executes(::delete)
                    }
                }

                literal("add_user") {
                    requires { Permissions.require("permissions.groups.add_user", 3).test(it) }
                    argument("player", GameProfileArgumentType.gameProfile()) {
                        argument("group", StringArgumentType.string()) {
                            executes(::addUser)
                        }
                    }
                }

                literal("remove_user") {
                    requires { Permissions.require("permissions.groups.remove_user", 3).test(it) }
                    argument("player", GameProfileArgumentType.gameProfile()) {
                        argument("group", StringArgumentType.string()) {
                            executes(::removeUser)
                        }
                    }
                }

                literal("members") {
                    requires { Permissions.require("permissions.groups.members", 3).test(it) }
                    argument("group", StringArgumentType.string()) {
                        executes(::members)
                    }
                }
            }
        }
    }

    private fun members(context: CommandContext<ServerCommandSource>): Int {
        val group = StringArgumentType.getString(context, "name")

        db.transaction {
            val rows = GroupUsers.select { GroupUsers.group.eq(group) }.toList()
            if (rows.isEmpty()) {
                "No members in group '$group' or group does not exist."
            } else {
                val cache = context.source.server.userCache
                rows.joinToString("\n") { "- ${cache.getByUuid(it[GroupUsers.user]).orElse(null)?.name ?: "Unknown user: ${it[GroupUsers.user]}"}" }
            }
        }.thenAccept {
            context.source.sendFeedback(LiteralText(it), false)
        }


        return 0
    }

    private fun create(context: CommandContext<ServerCommandSource>): Int {
        val group = StringArgumentType.getString(context, "name")

        db.transaction {
            val row = GroupPermissionTable.select { GroupPermissionTable.name.eq(group) }.firstOrNull()
            if (row != null) {
                "Group '$group' already exists!"
            } else {
                GroupPermissionTable.insert {
                    it[GroupPermissionTable.name] = group
                    it[GroupPermissionTable.permissionTree] =
                        NbtCompound().also { nbt -> PermissionTree("root", mutableListOf()).writeNbt(nbt) }
                }
                ""
            }
        }.thenAccept {
            if (it.isNotBlank()) {
                context.source.sendError(LiteralText(it))
            } else {
                context.source.sendFeedback(LiteralText("Group '$group' created."), true)
            }
        }

        return 0
    }

    private fun delete(context: CommandContext<ServerCommandSource>): Int {
        val group = StringArgumentType.getString(context, "name")

        db.transaction {
            val row = GroupPermissionTable.select { GroupPermissionTable.name.eq(group) }.firstOrNull()
            if (row == null) {
                "Group '$group' does not exist!"
            } else {
                GroupPermissionTable.deleteWhere { GroupPermissionTable.name.eq(group) }
                ""
            }
        }.thenAccept {
            if (it.isNotBlank()) {
                context.source.sendError(LiteralText(it))
            } else {
                context.source.sendFeedback(LiteralText("Group '$group' deleted."), true)
            }
        }

        return 0
    }

    private fun addUser(context: CommandContext<ServerCommandSource>): Int {
        val group = StringArgumentType.getString(context, "group")
        val user = GameProfileArgumentType.getProfileArgument(context, "player").first()

        db.transaction {
            val groupRow = GroupPermissionTable.select { GroupPermissionTable.name.eq(group) }.firstOrNull()
            if (groupRow == null) {
                "Group '$group' does not exist!"
            } else {
                val row = GroupUsers.select { GroupUsers.group.eq(group) and GroupUsers.user.eq(user.id) }.firstOrNull()
                if (row != null) {
                    "Player '${user.name}' is already in group '$group'!"
                } else {
                    GroupUsers.insert {
                        it[GroupUsers.group] = group
                        it[GroupUsers.user] = user.id
                    }
                    ""
                }
            }
        }.thenAccept {
            if (it.isNotBlank()) {
                context.source.sendError(LiteralText(it))
            } else {
                context.source.sendFeedback(LiteralText("User '${user.name}' added to group '$group'."), false)
            }
        }

        return 0
    }

    private fun removeUser(context: CommandContext<ServerCommandSource>): Int {
        val group = StringArgumentType.getString(context, "group")
        val user = GameProfileArgumentType.getProfileArgument(context, "player").first()

        if (group == "everyone") {
            context.source.sendError(LiteralText("Cannot remove player from group 'everyone'!"))
            return 0
        }

        db.transaction {
            val groupRow = GroupPermissionTable.select { GroupPermissionTable.name.eq(group) }.firstOrNull()
            if (groupRow == null) {
                "Group '$group' does not exist!"
            } else {
                val row = GroupUsers.select { GroupUsers.group.eq(group) and GroupUsers.user.eq(user.id) }.firstOrNull()
                if (row == null) {
                    "Player '${user.name}' is not in group '$group'!"
                } else {
                    GroupUsers.insert {
                        it[GroupUsers.group] = group
                        it[GroupUsers.user] = user.id
                    }
                    ""
                }
            }
        }.thenAccept {
            if (it.isNotBlank()) {
                context.source.sendError(LiteralText(it))
            } else {
                context.source.sendFeedback(LiteralText("User '${user.name}' removed from group '$group'."), false)
            }
        }

        return 0
    }
}