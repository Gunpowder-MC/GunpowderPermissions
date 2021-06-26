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

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import io.github.gunpowder.GunpowderPermissionProvider
import net.minecraft.command.CommandSource
import java.util.concurrent.CompletableFuture

class PermissionArgumentType : ArgumentType<String> {
    companion object {
        val ERROR = DynamicCommandExceptionType {
            LiteralMessage("Invalid character in permission: '$it'")
        }

        fun permission() = PermissionArgumentType()

        fun getPermission(context: CommandContext<*>, name: String): String {
            return context.getArgument(name, String::class.java)
        }
    }

    override fun parse(reader: StringReader): String {
        var permission = ""
        while (reader.canRead()) {
            val c = reader.read()
            if (c in '0'..'9' || c in 'A'..'Z' || c in 'a'..'z' || c == '_' || c == '-' || c == '.' || c == '*') {
                permission += c
            } else {
                throw ERROR.create(c)
            }
        }
        return permission
    }

    override fun <S : Any> listSuggestions(
        context: CommandContext<S>,
        builder: SuggestionsBuilder
    ): CompletableFuture<Suggestions> {
        return CommandSource.suggestMatching(
            GunpowderPermissionProvider.known.sorted(),
            builder
        )
    }

    override fun toString(): String {
        return "string()"
    }

    override fun getExamples(): Collection<String> {
        return listOf("permissions.edit.player.grant", "permissions.groups.*")
    }
}