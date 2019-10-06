package com.ivieleague.kbuild.kotlin

import com.ivieleague.kotlinparser.KotlinLexer
import com.ivieleague.kotlinparser.KotlinParser
import org.antlr.v4.runtime.ANTLRInputStream
import org.antlr.v4.runtime.CommonTokenStream
import java.io.File
import java.io.FileInputStream


internal fun File.publicDeclarations(into: MutableSet<String>) {
    return try {
        val lexer = KotlinLexer(ANTLRInputStream(FileInputStream(this)))
        val tokenStream = CommonTokenStream(lexer)
        val parser = KotlinParser(tokenStream)
        parser.kotlinFile().publicDeclarations(into)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

internal fun KotlinParser.KotlinFileContext.publicDeclarations(into: MutableSet<String>) {
    val packageName = this.packageHeader().identifier().text
    this.topLevelObject().forEach {
        it.declaration()?.publicDeclarations(packageName, into)
    }
}

internal fun KotlinParser.DeclarationContext.publicDeclarations(packageName: String, into: MutableSet<String>) {
    classDeclaration()?.publicDeclarations(packageName, into)
        ?: functionDeclaration()?.publicDeclarations(packageName, into)
        ?: objectDeclaration()?.publicDeclarations(packageName, into)
        ?: propertyDeclaration()?.publicDeclarations(packageName, into)
        ?: typeAlias()?.publicDeclarations(packageName, into)
}

internal fun KotlinParser.TypeAliasContext.publicDeclarations(packageName: String, into: MutableSet<String>) {
    if (this.modifiers().visibility() == Visibility.Public) {
        into += packageName + "." + this.text
    }
}

internal fun KotlinParser.PropertyDeclarationContext.publicDeclarations(
    packageName: String,
    into: MutableSet<String>
) {
    if (this.modifiers().visibility().isExposed) {
        this.variableDeclaration()?.let {
            val fullName = packageName + "." + it.simpleIdentifier().text + (this.typeParameters()?.let {
                it.text
            } ?: "") + ": " + (it.type()?.text ?: "Unknown") + (this.receiverType()?.let {
                " on " + it.text
            } ?: "")
            into += fullName
            if (this.VAR() != null) {
                into += fullName + " = X"
            }
        }
        this.multiVariableDeclaration()?.variableDeclaration()?.forEach {
            val fullName = packageName + "." + it.simpleIdentifier().text + (this.typeParameters()?.let {
                it.text
            } ?: "") + ": " + (it.type()?.text ?: "Unknown") + (this.receiverType()?.let {
                " on " + it.text
            } ?: "")
            into += fullName
            if (this.VAR() != null) {
                into += fullName + " = X"
            }
        }
    }
}

internal fun KotlinParser.ObjectDeclarationContext.publicDeclarations(packageName: String, into: MutableSet<String>) {
    if (modifiers().visibility().isExposed) {
        val fullName = packageName + "." + simpleIdentifier()?.text
        into += fullName
        delegationSpecifiers()?.publicDeclarations(fullName, into)
        classBody()?.classMemberDeclarations()?.publicDeclarations(fullName, into)
    }
}

internal fun KotlinParser.FunctionDeclarationContext.publicDeclarations(
    packageName: String,
    into: MutableSet<String>
) {
    if (modifiers().visibility().isExposed) {
        val functionName = packageName + "." + this.simpleIdentifier()?.text + (this.typeParameters()?.let {
            it.text
        } ?: "") + "(" + (this.functionValueParameters()?.let {
            it.functionValueParameter()
                .joinToString { it.parameter().simpleIdentifier().text + ": " + it.parameter().type().text }
        } ?: "") + "): " + (this.type()?.text ?: "Unit") + (this.receiverType()?.let {
            " on " + it.text
        } ?: "")
        into += functionName
        functionValueParameters()?.let {
            it.functionValueParameter()?.forEach {
                if (it.ASSIGNMENT() != null) {
                    into += functionName + " - " + it.parameter().simpleIdentifier().text + " = DEFAULT"
                }
            }
        }
    }
}

internal fun KotlinParser.ClassDeclarationContext.publicDeclarations(packageName: String, into: MutableSet<String>) {
    if (this.modifiers().visibility().isExposed) {
        val fullName = packageName + "." + this.simpleIdentifier().text
        into += fullName

        this.primaryConstructor()?.let {
            if (it.modifiers().visibility().isExposed) {
                val constructorName = fullName + "(" + (it.classParameters()?.let {
                    it.classParameter().joinToString { it.simpleIdentifier().text + ": " + it.type().text }
                } ?: "") + ")"
                into += constructorName
                it.classParameters()?.let {
                    it.classParameter()?.forEach {
                        if (it.ASSIGNMENT() != null) {
                            into += constructorName + " - " + it.simpleIdentifier().text + " = DEFAULT"
                        }
                    }
                }
            }
            it.classParameters()?.let {
                it.classParameter().forEach {
                    if (it.modifiers().visibility().isExposed && (it.VAL() != null || it.VAR() != null)) {
                        val propName =
                            fullName + "." + it.simpleIdentifier().text + ": " + (it.type()?.text ?: "Unknown")
                        into += propName
                        if (it.VAR() != null) {
                            into += propName + " = X"
                        }
                    }
                }
            }
        }

        this.delegationSpecifiers()?.publicDeclarations(fullName, into)

        this.enumClassBody()?.enumEntries()?.enumEntry()?.forEach {
            into += fullName + "." + it.simpleIdentifier().text
        }

        val declarations =
            this.enumClassBody()?.classMemberDeclarations() ?: this.classBody()?.classMemberDeclarations()
        declarations?.publicDeclarations(fullName, into)
    }
}

internal fun KotlinParser.DelegationSpecifiersContext.publicDeclarations(
    packageName: String,
    into: MutableSet<String>
) {
    annotatedDelegationSpecifier().forEach {
        val dg = it.delegationSpecifier()
        val extends = dg.constructorInvocation()?.userType()?.text ?: dg.functionType()?.text
        ?: dg.explicitDelegation()?.userType()?.text ?: dg.userType()?.text
        into += packageName + ": " + extends
    }
}

internal fun KotlinParser.ClassMemberDeclarationsContext.publicDeclarations(
    packageName: String,
    into: MutableSet<String>
) {
    classMemberDeclaration()?.forEach {
        it?.companionObject()?.let {
            if (it.modifiers().visibility().isExposed) {
                val companionName = packageName + "." + (it.simpleIdentifier()?.text ?: "Companion")
                into += companionName
                it.delegationSpecifiers()?.publicDeclarations(companionName, into)
                it.classBody()?.classMemberDeclarations()?.publicDeclarations(companionName, into)
            }
        }
        it?.secondaryConstructor()?.let {
            if (it.modifiers().visibility().isExposed) {
                val constructorName = packageName + "(" + (it.functionValueParameters()?.let {
                    it.functionValueParameter()
                        .joinToString { it.parameter().simpleIdentifier().text + ": " + it.parameter().type().text }
                } ?: "") + ")"
                into += constructorName
                it.functionValueParameters()?.let {
                    it.functionValueParameter()?.forEach {
                        if (it.ASSIGNMENT() != null) {
                            into += constructorName + " - " + it.parameter().simpleIdentifier().text + " = DEFAULT"
                        }
                    }
                }
            }
        }
        it?.declaration()?.publicDeclarations(packageName, into)
    }
}

internal enum class Visibility(val isExposed: Boolean) {
    Private(false),
    Internal(false),
    Protected(true),
    Public(true)
}

internal fun KotlinParser.ModifiersContext?.visibility(): Visibility {
    val v = this?.modifier()?.asSequence()?.mapNotNull { it.visibilityModifier() }?.firstOrNull()
    return when {
        v == null -> Visibility.Public
        v.INTERNAL() != null -> Visibility.Internal
        v.PRIVATE() != null -> Visibility.Private
        v.PROTECTED() != null -> Visibility.Protected
        v.PUBLIC() != null -> Visibility.Public
        else -> Visibility.Public
    }
}