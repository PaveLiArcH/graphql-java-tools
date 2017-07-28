package com.coxautodev.graphql.tools

import graphql.Scalars
import graphql.language.FieldDefinition
import graphql.language.TypeName
import graphql.schema.DataFetchingEnvironment
import org.apache.commons.lang3.ClassUtils
import org.apache.commons.lang3.reflect.FieldUtils
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * @author Andrew Potter
 */
internal class FieldResolverScanner {

    companion object {
        fun getAllMethods(type: Class<*>): List<Method> {
            return type.declaredMethods.toList() + ClassUtils.getAllSuperclasses(type).flatMap { it.declaredMethods.toList() }
        }
    }

    fun findFieldResolver(field: FieldDefinition, resolverInfo: ResolverInfo): FieldResolver {
        val searches = resolverInfo.getFieldSearches()

        val scanProperties = field.inputValueDefinitions.isEmpty()
        val found = searches.mapNotNull { search -> findFieldResolver(field, search, scanProperties) }

        if(resolverInfo is RootResolverInfo && found.size > 1) {
            throw FieldResolverError("Found more than one matching resolver for field '$field': $found")
        }

        return found.firstOrNull() ?: throw FieldResolverError(getMissingFieldMessage(field, searches, scanProperties))
    }

    private fun findFieldResolver(field: FieldDefinition, search: Search, scanProperties: Boolean): FieldResolver? {
        val method = findResolverMethod(field, search)
        if(method != null) {
            return MethodFieldResolver(field, search, method.apply { isAccessible = true })
        }

        if(scanProperties) {
            val property = findResolverProperty(field, search)
            if(property != null) {
                return PropertyFieldResolver(field, search, property.apply { isAccessible = true })
            }
        }

        return null
    }

    private fun isBoolean(type: GraphQLLangType) = type.unwrap().let { it is TypeName && it.name == Scalars.GraphQLBoolean.name }

    private fun findResolverMethod(field: FieldDefinition, search: Search): java.lang.reflect.Method? {

        val methods = getAllMethods(search.type)
        val argumentCount = field.inputValueDefinitions.size + if(search.requiredFirstParameterType != null) 1 else 0
        val name = field.name

        val isBoolean = isBoolean(field.type)

        // Check for the following one by one:
        //   1. Method with exact field name
        //   2. Method that returns a boolean with "is" style getter
        //   3. Method with "get" style getter
        return methods.find {
            it.name == name && verifyMethodArguments(it, argumentCount, search)
        } ?: methods.find {
            (isBoolean && it.name == "is${name.capitalize()}") && verifyMethodArguments(it, argumentCount, search)
        } ?: methods.find {
            it.name == "get${name.capitalize()}" && verifyMethodArguments(it, argumentCount, search)
        }
    }

    private fun verifyMethodArguments(method: java.lang.reflect.Method, requiredCount: Int, search: Search): Boolean {
        val correctParameterCount = method.parameterCount == requiredCount || (method.parameterCount == (requiredCount + 1) && method.parameterTypes.last() == DataFetchingEnvironment::class.java)
        val appropriateFirstParameter = if(search.requiredFirstParameterType != null) method.parameterTypes.firstOrNull() == search.requiredFirstParameterType else true
        return correctParameterCount && appropriateFirstParameter
    }


    private fun findResolverProperty(field: FieldDefinition, search: Search): Field? {
        return FieldUtils.getAllFields(search.type).find { it.name == field.name }
    }

    fun getMissingFieldMessage(field: FieldDefinition, searches: List<Search>, scannedProperties: Boolean): String {
        val signatures = mutableListOf("")
        val isBoolean = isBoolean(field.type)

        searches.forEach { search ->
            signatures.addAll(getMissingMethodSignatures(field, search, isBoolean, scannedProperties))
        }

        return "No method${if(scannedProperties) " or field" else ""} found with any of the following signatures (with or without ${DataFetchingEnvironment::class.java.name} as the last argument), in priority order:\n${signatures.joinToString("\n  ")}"
    }


    fun getMissingMethodSignatures(field: FieldDefinition, search: Search, isBoolean: Boolean, scannedProperties: Boolean): List<String> {
        val baseType = search.type
        val signatures = mutableListOf<String>()
        val args = mutableListOf<String>()
        val sep = ", "

        if(search.requiredFirstParameterType != null) {
            args.add(search.requiredFirstParameterType.name)
        }

        args.addAll(field.inputValueDefinitions.map { "~${it.name}" })

        val argString = args.joinToString(sep)

        signatures.add("${baseType.name}.${field.name}($argString)")
        if(isBoolean) {
            signatures.add("${baseType.name}.is${field.name.capitalize()}($argString)")
        }
        signatures.add("${baseType.name}.get${field.name.capitalize()}($argString)")
        if(scannedProperties) {
            signatures.add("${baseType.name}.${field.name}")
        }

        return signatures
    }

    data class Search(val type: Class<*>, val resolverInfo: ResolverInfo, val source: Any?, val requiredFirstParameterType: Class<*>? = null)
}

class FieldResolverError(msg: String): RuntimeException(msg)