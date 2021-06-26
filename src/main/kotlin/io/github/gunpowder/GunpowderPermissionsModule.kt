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

import com.mojang.brigadier.arguments.StringArgumentType
import io.github.gunpowder.api.GunpowderMod
import io.github.gunpowder.api.GunpowderModule
import io.github.gunpowder.api.builders.ArgumentType
import io.github.gunpowder.commands.GroupCommand
import io.github.gunpowder.commands.PermissionCommand
import io.github.gunpowder.entities.PermissionArgumentType
import io.github.gunpowder.entities.PermissionTree
import io.github.gunpowder.events.PermissionRegisterCallback
import io.github.gunpowder.models.GroupPermissionTable
import io.github.gunpowder.models.GroupUsers
import io.github.gunpowder.models.UserPermissionTable
import kotlinx.serialization.serializer
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.command.argument.ArgumentTypes
import net.minecraft.command.argument.serialize.ConstantArgumentSerializer
import net.minecraft.nbt.NbtCompound
import net.minecraft.util.Identifier
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select

object GunpowderPermissionsModule : GunpowderModule {
    override val name = "permissions"
    override val toggleable = true
    override val priority = 0
    val gunpowder: GunpowderMod
        get() = GunpowderMod.instance

    override fun onInitialize() {
        ArgumentType.builder {
            id("permission")
            type(PermissionArgumentType::class)
            serializer(ConstantArgumentSerializer(PermissionArgumentType.Companion::permission))
            fallback { StringArgumentType.word() }
            suggestions(GunpowderPermissionProvider)
        }
    }

    override fun registerTables() {
        gunpowder.registry.registerTable(UserPermissionTable)
        gunpowder.registry.registerTable(GroupPermissionTable)
        gunpowder.registry.registerTable(GroupUsers)

        gunpowder.database.transaction {
            val row = GroupPermissionTable.select { GroupPermissionTable.name.eq("everyone") }.firstOrNull()

            if (row == null) {
                GroupPermissionTable.insert {
                    it[GroupPermissionTable.name] = "everyone"
                    it[GroupPermissionTable.permissionTree] =
                        NbtCompound().also { nbt -> PermissionTree("root", mutableListOf()).writeNbt(nbt) }
                }
            }
        }
    }

    override fun registerCommands() {
        gunpowder.registry.registerCommand(GroupCommand::register)
        gunpowder.registry.registerCommand(PermissionCommand::register)
    }

    override fun registerEvents() {
        PermissionRegisterCallback.EVENT.register {
            GunpowderPermissionProvider.known.add(it)
        }

        ServerPlayConnectionEvents.JOIN.register { serverPlayNetworkHandler, packetSender, minecraftServer ->
            gunpowder.database.transaction {
                val row =
                    GroupUsers.select { GroupUsers.group.eq("everyone") and GroupUsers.user.eq(serverPlayNetworkHandler.player.uuid) }
                        .firstOrNull()

                if (row == null) {
                    GroupUsers.insert {
                        it[GroupUsers.user] = serverPlayNetworkHandler.player.uuid
                        it[GroupUsers.group] = "everyone"
                    }
                }
            }
        }
    }
}
