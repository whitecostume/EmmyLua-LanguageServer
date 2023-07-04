/*
 * Copyright (c) 2017. tangzx(love.tangzx@qq.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tang.intellij.lua.ty

import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubInputStream
import com.intellij.psi.stubs.StubOutputStream
import com.intellij.util.Processor
import com.intellij.util.io.StringRef
import com.tang.intellij.lua.Constants
import com.tang.intellij.lua.comment.psi.LuaDocTableDef
import com.tang.intellij.lua.comment.psi.LuaDocTagClass
import com.tang.intellij.lua.project.LuaSettings
import com.tang.intellij.lua.psi.*
import com.tang.intellij.lua.psi.search.LuaClassInheritorsSearch
import com.tang.intellij.lua.psi.search.LuaShortNamesManager
import com.tang.intellij.lua.search.SearchContext
import com.tang.intellij.lua.stubs.index.LuaClassMemberIndex

interface ITyClass : ITy {
    val className: String
    val varName: String
    var superClassName: String?
    var interfaceNames: List<String>?
    var aliasName: String?
    var isInterface: Boolean
    var isEnum: Boolean
    fun processAlias(processor: Processor<String>): Boolean
    fun lazyInit(searchContext: SearchContext)
    fun processMembers(context: SearchContext, processor: (ITyClass, LuaClassMember) -> Unit, deep: Boolean = true)
    fun processMembers(context: SearchContext, processor: (ITyClass, LuaClassMember) -> Unit) {
        processMembers(context, processor, true)
    }

    fun processVisibleMembers(context: SearchContext, contextTy: ITy, processor: (ITyClass, LuaClassMember) -> Unit) {
        val noVisibleSet = mutableSetOf<String>()
        val list = mutableListOf<LuaClassMember>()

        processMembers(context) { curType, member ->
            if (curType.isVisibleInScope(
                    context.project,
                    contextTy,
                    member.visibility
                )
            ) {
                list.add(member)
            } else {
                member.name?.let {
                    noVisibleSet.add(it)
                }
            }
        }

        for (member in list) {
            val name = member.name
            if (name != null && !noVisibleSet.contains(name)) {
                processor(this, member)
            }
        }
    }

    fun getIndexResultType(element: LuaLiteralExpr): ITy?
    fun findOriginMember(name: String, searchContext: SearchContext): LuaClassMember?
    fun findMember(name: String, searchContext: SearchContext): LuaClassMember?
    fun findMemberType(name: String, searchContext: SearchContext): ITy?
    fun findSuperMember(name: String, searchContext: SearchContext): LuaClassMember?

    fun recoverAlias(context: SearchContext, aliasSubstitutor: TyAliasSubstitutor): ITy {
        return this
    }

    fun getClassCallType(context: SearchContext): ITyFunction?
}

fun ITyClass.isVisibleInScope(project: Project, contextTy: ITy, visibility: Visibility): Boolean {
    if (visibility == Visibility.PUBLIC)
        return true
    var isVisible = false
    TyUnion.process(contextTy) {
        if (it is ITyClass) {
            if (it == this)
                isVisible = true
            else if (visibility == Visibility.PROTECTED) {
                isVisible = LuaClassInheritorsSearch.isClassInheritFrom(
                    GlobalSearchScope.projectScope(project),
                    project,
                    className,
                    it.className
                )
            }
        }
        !isVisible
    }
    return isVisible
}

abstract class TyClass(
    override val className: String,
    override val varName: String = "",
    override var superClassName: String? = null,
    override var interfaceNames: List<String>? = null,
    override var isInterface: Boolean = false,
    override var isEnum: Boolean = false
) : Ty(TyKind.Class), ITyClass {
    final override var aliasName: String? = null
    private var _lazyInitialized: Boolean = false

    override fun equals(other: Any?): Boolean {
        return other is ITyClass && other.className == className && other.flags == flags
    }

    override fun hashCode(): Int {
        return className.hashCode()
    }

    override fun processAlias(processor: Processor<String>): Boolean {
        val alias = aliasName
        if (alias == null || alias == className)
            return true
        if (!processor.process(alias))
            return false
        if (!isGlobal && !isAnonymous && LuaSettings.instance.isRecognizeGlobalNameAsType)
            return processor.process(getGlobalTypeName(className))
        return true
    }

    override fun processMembers(context: SearchContext, processor: (ITyClass, LuaClassMember) -> Unit, deep: Boolean) {
        val clazzName = className
        val project = context.project

        val manager = LuaShortNamesManager.getInstance(project)
        val members = manager.getClassMembers(clazzName, context)
        val list = mutableListOf<LuaClassMember>()
        list.addAll(members)

        processAlias(Processor { alias ->
            val classMembers = manager.getClassMembers(alias, context)
            list.addAll(classMembers)
        })

        for (member in list) {
            processor(this, member)
        }

        // super
        if (deep) {
            processSuperClass(this, context) {
                it.processMembers(context, processor, false)
                true
            }
        }
    }

    override fun getIndexResultType(element: LuaLiteralExpr): ITy? {
        val context = SearchContext.get(element.project)
        when (element.kind) {
            LuaLiteralKind.Number -> {
                var member = LuaClassMemberIndex.find(this, "[${element.text}]", context)
                if (member == null) {
                    member = LuaClassMemberIndex.find(this, "[number]", context)
                }

                return member?.guessType(context)
            }

            LuaLiteralKind.String -> {
                var member = LuaClassMemberIndex.find(this, element.text, context)
                if (member == null) {
                    member = LuaClassMemberIndex.find(this, "[string]", context)
                }

                return member?.guessType(context)
            }

            else -> {}
        }
        return null
    }

    override fun findOriginMember(name: String, searchContext: SearchContext): LuaClassMember? {
        return LuaClassMemberIndex.findOrigin(this, name, searchContext)
    }

    override fun findMember(name: String, searchContext: SearchContext): LuaClassMember? {
        return LuaShortNamesManager.getInstance(searchContext.project).findMember(this, name, searchContext)
    }

    override fun findMemberType(name: String, searchContext: SearchContext): ITy? {
        return infer(findMember(name, searchContext), searchContext)
    }

    override fun findSuperMember(name: String, searchContext: SearchContext): LuaClassMember? {
        // Travel up the hierarchy to find the lowest member of this type on a superclass (excluding this class)
        var member: LuaClassMember? = null
        processSuperClass(this, searchContext) {
            member = it.findMember(name, searchContext)
            member == null
        }
        return member
    }

    override fun accept(visitor: ITyVisitor) {
        visitor.visitClass(this)
    }

    override fun lazyInit(searchContext: SearchContext) {
        if (!_lazyInitialized) {
            _lazyInitialized = true
            doLazyInit(searchContext)
        }
    }

    open fun doLazyInit(searchContext: SearchContext) {
        val classDef = LuaShortNamesManager.getInstance(searchContext.project).findClass(className, searchContext)
        if (classDef != null && aliasName == null) {
            val tyClass = classDef.type
            aliasName = tyClass.aliasName
            superClassName = tyClass.superClassName
            interfaceNames = tyClass.interfaceNames
            isInterface = tyClass.isInterface
            isEnum = tyClass.isEnum
        }
    }

    override fun getSuperClass(context: SearchContext): ITy? {
        lazyInit(context)
        val clsName = superClassName
        if (clsName != null && clsName != className) {
            return Ty.getBuiltin(clsName) ?: LuaShortNamesManager.getInstance(context.project)
                .findClass(clsName, context)?.type
        }
        return null
    }

    override fun getInterfaces(context: SearchContext): List<ITyClass>? {
        if (interfaceNames == null) {
            return null
        }

        val result = mutableListOf<ITyClass>()
        interfaceNames!!.forEach {
            val ty = Ty.getBuiltin(it) ?: LuaShortNamesManager.getInstance(context.project)
                .findClass(it, context)?.type
            if (ty != null && ty is ITyClass) {
                ty.lazyInit(context)
                if (ty.isInterface) {
                    result.add(ty)
                }
            }
        }
        return result
    }

    override fun getClassCallType(context: SearchContext): ITyFunction? {
        val ty = findMemberType("ctor", context)
        if (ty is ITyFunction) {
            return ty
        }
        return null
    }

    override fun subTypeOf(other: ITy, context: SearchContext, strict: Boolean): Boolean {
        // class extends table
        if (other == Ty.TABLE) return true
        if (other is TyGeneric && other.base == Ty.TABLE) return true
        if (super.subTypeOf(other, context, strict)) return true

        // Lazy init for superclass
        this.doLazyInit(context)
        // Check if any of the superclasses are type
        var isSubType = false
        processSuperClass(this, context) { superType ->
            isSubType = superType == other
            !isSubType
        }

        if (!isSubType && (other is ITyClass)) {
            other.lazyInit(context)
            if (other.isInterface) {
                isSubType = true
                other.processMembers(context, { _, member ->
                    if (member.name == null) {
                        isSubType = false
                        return@processMembers
                    }
                    val thisMember = findMember(member.name!!, context)
                    if (thisMember == null) {
                        isSubType = false
                        return@processMembers
                    }

                    val thisMemberType = thisMember.guessType(context)
                    val interfaceMemberType = member.guessType(context)
                    if (!thisMemberType.subTypeOf(interfaceMemberType, context, strict)) {
                        isSubType = false
                        return@processMembers
                    }

                }, false)
            }
        }

        return isSubType
    }

    override fun substitute(substitutor: ITySubstitutor): ITy {
        return substitutor.substitute(this)
    }

    companion object {
        // for _G
        val G: TyClass = createSerializedClass(Constants.WORD_G)

        fun createAnonymousType(nameDef: LuaNameDef): TyClass {
            val stub = nameDef.stub
            val tyName = stub?.anonymousType ?: getAnonymousType(nameDef)
            return createSerializedClass(tyName, nameDef.name, null, null, TyFlags.ANONYMOUS)
        }

        fun createGlobalType(nameExpr: LuaNameExpr, store: Boolean): ITy {
            val name = nameExpr.name
            val g = createSerializedClass(getGlobalTypeName(nameExpr), name, null, null, TyFlags.GLOBAL)
            if (!store && LuaSettings.instance.isRecognizeGlobalNameAsType)
                return createSerializedClass(name, name, null, null, TyFlags.GLOBAL).union(g)
            return g
        }

        fun createGlobalType(name: String): ITy {
            val g = createSerializedClass(getGlobalTypeName(name), name, null, null, TyFlags.GLOBAL)
            if (LuaSettings.instance.isRecognizeGlobalNameAsType)
                return createSerializedClass(name, name, null, null, TyFlags.GLOBAL).union(g)
            return g
        }

        fun processSuperClass(
            start: ITyClass,
            searchContext: SearchContext,
            processor: (ITyClass) -> Boolean
        ): Boolean {
            val processedName = mutableSetOf<String>()
            var cur: ITy? = start
            while (cur != null) {
                val cls = cur.getSuperClass(searchContext)
                if (cls is ITyClass) {
                    if (!processedName.add(cls.className)) {
                        // todo: Infinite inheritance
                        return false
                    }
                    if (!processor(cls))
                        return false
                    if (cls.isInterface) {
                        break
                    }
                }
                cur = cls
            }
            return processInterface(start, searchContext, processor)
        }

        fun processInterface(
            cls: ITyClass,
            searchContext: SearchContext,
            processor: (ITyClass) -> Boolean
        ): Boolean {
            val processedName = mutableSetOf<String>()
            return innerProcessorInterface(cls, searchContext, processedName, processor)
        }

        private fun innerProcessorInterface(
            cls: ITyClass,
            searchContext: SearchContext,
            processName: MutableSet<String>,
            processor: (ITyClass) -> Boolean
        ): Boolean {
            val interfaces = cls.getInterfaces(searchContext)
            if (interfaces != null) {
                for (tyInterface in interfaces) {
                    if (tyInterface is ITyClass) {
                        if (!processName.add(tyInterface.className)) {
                            continue
                        }

                        if (!processor(tyInterface)) {
                            return false
                        }

                        if (!innerProcessorInterface(tyInterface, searchContext, processName, processor)) {
                            return false
                        }
                    }
                }
            }

            return true
        }
    }
}

class TyPsiDocClass(tagClass: LuaDocTagClass) : TyClass(tagClass.name) {

    init {
        val supperRef = tagClass.superClassNameRef
        if (supperRef != null) {
            val classList = supperRef.classNameRefList
            if (classList.size != 0) {
                superClassName = classList[0].text
                interfaceNames = classList.map { it.text }
            }
        }
        if (tagClass.`interface` != null) {
            isInterface = true
        } else if (tagClass.enum != null) {
            isEnum = true
        }

        aliasName = tagClass.aliasName
    }

    override fun doLazyInit(searchContext: SearchContext) {}
}

open class TySerializedClass(
    name: String,
    varName: String = name,
    supper: String? = null,
    alias: String? = null,
    flags: Int = 0
) : TyClass(name, varName, supper) {
    init {
        aliasName = alias
        this.flags = flags

    }

    override fun recoverAlias(context: SearchContext, aliasSubstitutor: TyAliasSubstitutor): ITy {
        if (this.isAnonymous || this.isGlobal)
            return this
        val alias = LuaShortNamesManager.getInstance(context.project).findAlias(className, context)
        return alias?.type?.substitute(aliasSubstitutor) ?: this
    }
}

//todo Lazy class ty
class TyLazyClass(name: String) : TySerializedClass(name)

fun createSerializedClass(
    name: String,
    varName: String = name,
    supper: String? = null,
    alias: String? = null,
    flags: Int = 0
): TyClass {
    val list = name.split("|")
    if (list.size == 3) {
        val type = list[0].toInt()
        if (type == 10) {
            return TySerializedDocTable(name)
        }
    }

    return TySerializedClass(name, varName, supper, alias, flags)
}

fun getTableTypeName(table: LuaTableExpr): String {
    val stub = table.stub
    if (stub != null)
        return stub.tableTypeName

    val fileName = table.containingFile.name
    return "$fileName@(${table.node.startOffset})table"
}

fun getAnonymousType(nameDef: LuaNameDef): String {
    return "${nameDef.node.startOffset}@${nameDef.containingFile.name}"
}

fun getGlobalTypeName(text: String): String {
    return if (text == Constants.WORD_G) text else "$$text"
}

fun getGlobalTypeName(nameExpr: LuaNameExpr): String {
    return getGlobalTypeName(nameExpr.name)
}

class TyTable(val table: LuaTableExpr) : TyClass(getTableTypeName(table)) {
    init {
        this.flags = TyFlags.ANONYMOUS or TyFlags.ANONYMOUS_TABLE
    }

    override fun processMembers(context: SearchContext, processor: (ITyClass, LuaClassMember) -> Unit, deep: Boolean) {
        for (field in table.tableFieldList) {
            processor(this, field)
        }
        super.processMembers(context, processor, deep)
    }

    override fun toString(): String = displayName

    override fun doLazyInit(searchContext: SearchContext) = Unit

    override fun subTypeOf(other: ITy, context: SearchContext, strict: Boolean): Boolean {
        // Empty list is a table, but subtype of all lists
        return super.subTypeOf(
            other,
            context,
            strict
        ) || other == Ty.TABLE || (other is TyArray && table.tableFieldList.size == 0)
    }
}

fun getDocTableTypeName(table: LuaDocTableDef): String {
    val stub = table.stub
    if (stub != null)
        return stub.className

    val fileName = table.containingFile.name
    return "10|$fileName|${table.node.startOffset}"
}

class TyDocTable(val table: LuaDocTableDef) : TyClass(getDocTableTypeName(table)) {
    override fun doLazyInit(searchContext: SearchContext) {}

    override fun processMembers(context: SearchContext, processor: (ITyClass, LuaClassMember) -> Unit, deep: Boolean) {
        table.tableFieldList.forEach {
            processor(this, it)
        }
    }

    override fun findMember(name: String, searchContext: SearchContext): LuaClassMember? {
        return table.tableFieldList.firstOrNull { it.name == name }
    }
}

class TySerializedDocTable(name: String) : TySerializedClass(name) {
    override fun recoverAlias(context: SearchContext, aliasSubstitutor: TyAliasSubstitutor): ITy {
        return this
    }
}

object TyClassSerializer : TySerializer<ITyClass>() {
    override fun deserializeTy(flags: Int, stream: StubInputStream): ITyClass {
        val className = stream.readName()
        val varName = stream.readName()
        val superName = stream.readName()
        val aliasName = stream.readName()
        return createSerializedClass(
            StringRef.toString(className),
            StringRef.toString(varName),
            StringRef.toString(superName),
            StringRef.toString(aliasName),
            flags
        )
    }

    override fun serializeTy(ty: ITyClass, stream: StubOutputStream) {
        stream.writeName(ty.className)
        stream.writeName(ty.varName)
        stream.writeName(ty.superClassName)
        stream.writeName(ty.aliasName)
    }
}